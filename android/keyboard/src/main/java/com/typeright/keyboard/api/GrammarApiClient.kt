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

    /** Monthly AI budget paused: rule-only result, no quota charge, `ai_notice` explains it. */
    PAUSED("paused"),

    /** Not AI-eligible (> 150 chars or < 2 Hangul syllables): rule-only, silent. */
    SKIPPED("skipped"),

    /** Daily AI attempt cap reached (separate from the 훈수 quota; ads don't lift it): rule-only, silent. */
    RATE_LIMITED("rate_limited"),

    /** Also used for values this client version does not know. */
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
    /** e.g. "오늘 AI 선생님이 퇴근했습니다 😴" when [aiStatus] is PAUSED; null otherwise. */
    val aiNotice: String? = null,
)

data class MeResponse(val userId: String, val isPro: Boolean, val quota: Quota?, val aiPaused: Boolean = false)

/** Outcome of `POST /v1/billing/play/verify` — what the *server* concluded about the purchase token. */
enum class PlayVerifyResult(val apiValue: String) {
    /** PRO is on. */
    ACTIVATED("activated"),

    /** Google knows the purchase but it no longer entitles PRO (expired, cancelled, refunded). */
    INACTIVE("inactive"),
    UNKNOWN_PRODUCT("unknown_product"),

    /** Google has never seen this token: a forged or already-consumed receipt. */
    NOT_FOUND("not_found"),

    /** That receipt already belongs to a different account. */
    ALREADY_CLAIMED("already_claimed"),

    /** Play or the key was unreachable — retry later; the user's existing PRO is untouched. */
    UNAVAILABLE("unavailable");

    companion object {
        fun fromApi(value: String?): PlayVerifyResult = entries.firstOrNull { it.apiValue == value } ?: UNAVAILABLE
    }
}

data class PlayVerifyResponse(val result: PlayVerifyResult, val isPro: Boolean, val quota: Quota?)

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

    /**
     * Hands a Play purchase token to the server, which checks it with Google and grants PRO. The client never
     * decides entitlement, so a patched APK cannot award itself PRO.
     */
    suspend fun verifyPlayPurchase(productId: String, purchaseToken: String): ApiResult<PlayVerifyResponse> {
        val body = buildJsonObject {
            put("product_id", JsonPrimitive(productId))
            put("purchase_token", JsonPrimitive(purchaseToken))
        }.toString()
        return send("POST", "/v1/billing/play/verify", body, CHECK_CONNECT_TIMEOUT_MS, BILLING_READ_TIMEOUT_MS) {
            parsePlayVerify(it)
        }
    }

    /** Account deletion: `DELETE /v1/me` with the bearer token → 204. */
    suspend fun deleteMe(): ApiResult<Unit> =
        send("DELETE", "/v1/me", null, CHECK_CONNECT_TIMEOUT_MS, CHECK_READ_TIMEOUT_MS) { }

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
            if (response.code == 401) {
                if (attempt == 0) {
                    // Refresh once and retry. A failed refresh has already signed out (token revoked) or kept the
                    // session for later (offline), so no extra handling here.
                    attempt++
                    authHeaders = auth.authHeadersAfterUnauthorized()
                        ?: return ApiResult.Failure(ApiResult.Failure.Kind.UNAUTHENTICATED, 401)
                    continue
                }
                // Still 401 with a fresh token: the backend no longer accepts this session → signed-out state.
                auth.onAuthRejected()
                return ApiResult.Failure(ApiResult.Failure.Kind.UNAUTHENTICATED, 401)
            }
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
            aiNotice = root.str("ai_notice")?.takeIf { it.isNotBlank() },
        )
    }

    internal fun parseMe(body: String): MeResponse? {
        val root = json.parseToJsonElement(body).jsonObject
        val userId = root.str("user_id") ?: return null
        val quota = (root["quota"] as? JsonObject)?.let(::parseQuota)
        return MeResponse(userId, root.bool("is_pro") ?: quota?.isPro ?: false, quota, root.bool("ai_paused") ?: false)
    }

    internal fun parsePlayVerify(body: String): PlayVerifyResponse? {
        val root = json.parseToJsonElement(body).jsonObject
        val result = PlayVerifyResult.fromApi(root.str("result") ?: return null)
        val quota = (root["quota"] as? JsonObject)?.let(::parseQuota)
        return PlayVerifyResponse(result, root.bool("is_pro") ?: quota?.isPro ?: false, quota)
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

        /** Verification waits on Google's Play API, which is slower than a grammar check. */
        const val BILLING_READ_TIMEOUT_MS = 15_000
        private const val PLATFORM_HEADER = "X-Client-Platform"
    }
}
