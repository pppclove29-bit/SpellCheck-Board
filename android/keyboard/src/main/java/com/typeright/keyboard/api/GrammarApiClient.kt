package com.typeright.keyboard.api

import com.typeright.keyboard.account.Quota
import com.typeright.keyboard.auth.AuthHeaderProvider
import com.typeright.keyboard.rules.Correction
import com.typeright.keyboard.rules.CorrectionSource
import com.typeright.keyboard.rules.Feedback
import com.typeright.keyboard.rules.FeedbackMode
import com.typeright.keyboard.rules.RuleEngine
import com.typeright.keyboard.rules.SuggestionType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException

enum class AiStatus(val apiValue: String) {
    USED("used"),
    QUOTA_EXCEEDED("quota_exceeded"),
    UNAVAILABLE("unavailable");

    companion object {
        fun fromApi(value: String?): AiStatus = entries.firstOrNull { it.apiValue == value } ?: UNAVAILABLE
    }
}

data class GrammarCheckResponse(
    val originalText: String,
    val hasError: Boolean,
    val correctedText: String,
    val witFeedback: String?,
    val suggestions: List<Correction>,
    val engine: String,
    val aiStatus: AiStatus,
    val quota: Quota?,
)

data class MeResponse(val userId: String, val isPro: Boolean, val quota: Quota?)

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val kind: Kind, val httpCode: Int? = null, val message: String? = null) : ApiResult<Nothing> {
        enum class Kind { NETWORK, UNAUTHENTICATED, HTTP, INVALID_RESPONSE, INVALID_REQUEST }
    }
}

/**
 * REST client for docs/api-contract.md (`/v1/grammar-check`, `/v1/me`). All I/O runs on [ioDispatcher]; failures
 * are returned (never thrown) so the keyboard can silently fall back to on-device results.
 */
class GrammarApiClient(
    baseUrl: String,
    private val auth: AuthHeaderProvider,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun grammarCheck(text: String, mode: FeedbackMode): ApiResult<GrammarCheckResponse> {
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH) return ApiResult.Failure(ApiResult.Failure.Kind.INVALID_REQUEST)
        val body = buildJsonObject {
            put("text", JsonPrimitive(text))
            put("mode", JsonPrimitive(mode.apiValue))
        }.toString()
        return send("POST", "/v1/grammar-check", body, CHECK_CONNECT_TIMEOUT_MS, CHECK_READ_TIMEOUT_MS) {
            parseGrammarCheck(text, it)
        }
    }

    suspend fun me(): ApiResult<MeResponse> =
        send("GET", "/v1/me", null, CHECK_CONNECT_TIMEOUT_MS, CHECK_READ_TIMEOUT_MS) { parseMe(it) }

    private suspend fun <T> send(
        method: String,
        path: String,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        parse: (String) -> T?,
    ): ApiResult<T> {
        var authHeaders = auth.authHeaders()
            ?: return ApiResult.Failure(ApiResult.Failure.Kind.UNAUTHENTICATED)
        var attempt = 0
        while (true) {
            val request = HttpRequest(
                method = method,
                url = baseUrl + path,
                headers = authHeaders + (PLATFORM_HEADER to "android"),
                body = body,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
            )
            val response = try {
                runInterruptible(ioDispatcher) { transport.execute(request) }
            } catch (e: IOException) {
                return ApiResult.Failure(ApiResult.Failure.Kind.NETWORK, message = e.message)
            } catch (e: RuntimeException) {
                // e.g. SecurityException (cleartext blocked), IllegalArgumentException (bad URL)
                return ApiResult.Failure(ApiResult.Failure.Kind.NETWORK, message = e.message)
            }
            if (response.code == 401 && attempt == 0) {
                attempt++
                authHeaders = auth.authHeadersAfterUnauthorized()
                    ?: return ApiResult.Failure(ApiResult.Failure.Kind.UNAUTHENTICATED, 401)
                continue
            }
            if (response.code == 401) return ApiResult.Failure(ApiResult.Failure.Kind.UNAUTHENTICATED, 401)
            if (response.code !in 200..299) {
                return ApiResult.Failure(ApiResult.Failure.Kind.HTTP, response.code, errorCode(response.body))
            }
            val parsed = runCatching { parse(response.body) }.getOrNull()
                ?: return ApiResult.Failure(ApiResult.Failure.Kind.INVALID_RESPONSE, response.code)
            return ApiResult.Success(parsed)
        }
    }

    private fun errorCode(body: String): String? = runCatching {
        (json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.str("code")
    }.getOrNull()

    /**
     * Parses a grammar-check response. Suggestions are validated against the text that was sent
     * (`sent.substring(offset, offset + length) == original_word`) and re-filtered to be sorted/non-overlapping.
     */
    internal fun parseGrammarCheck(sent: String, body: String): GrammarCheckResponse {
        val root = json.parseToJsonElement(body).jsonObject
        val suggestions = root["suggestions"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val offset = o.int("offset") ?: return@mapNotNull null
            val length = o.int("length") ?: return@mapNotNull null
            val original = o.str("original_word") ?: return@mapNotNull null
            val suggested = o.str("suggested_word") ?: return@mapNotNull null
            if (offset < 0 || length <= 0 || offset + length > sent.length) return@mapNotNull null
            if (sent.substring(offset, offset + length) != original || suggested == original) return@mapNotNull null
            Correction(
                offset = offset,
                length = length,
                originalWord = original,
                suggestedWord = suggested,
                reason = o.str("reason").orEmpty(),
                type = SuggestionType.fromApi(o.str("type")) ?: Feedback.ruleSuggestionType(original, suggested),
                source = CorrectionSource.fromApi(o.str("source")),
            )
        }.let(RuleEngine::selectNonOverlapping)

        return GrammarCheckResponse(
            originalText = root.str("original_text") ?: sent,
            hasError = root.bool("has_error") ?: suggestions.isNotEmpty(),
            correctedText = root.str("corrected_text") ?: Feedback.applyCorrections(sent, suggestions),
            witFeedback = root.str("wit_feedback")?.takeIf { it.isNotBlank() },
            suggestions = suggestions,
            engine = root.str("engine") ?: "rule",
            aiStatus = AiStatus.fromApi(root.str("ai_status")),
            quota = (root["quota"] as? JsonObject)?.let(::parseQuota),
        )
    }

    internal fun parseMe(body: String): MeResponse? {
        val root = json.parseToJsonElement(body).jsonObject
        val userId = root.str("user_id") ?: return null
        val quota = (root["quota"] as? JsonObject)?.let(::parseQuota)
        return MeResponse(userId, root.bool("is_pro") ?: quota?.isPro ?: false, quota)
    }

    private fun parseQuota(o: JsonObject): Quota? {
        val remaining = o.int("remaining") ?: return null
        return Quota(
            isPro = o.bool("is_pro") ?: false,
            limit = o.int("limit") ?: 0,
            used = o.int("used") ?: 0,
            bonus = o.int("bonus") ?: 0,
            remaining = remaining,
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val MAX_TEXT_LENGTH = 1000
        const val CHECK_CONNECT_TIMEOUT_MS = 3_000
        const val CHECK_READ_TIMEOUT_MS = 6_000
        private const val PLATFORM_HEADER = "X-Client-Platform"
    }
}
