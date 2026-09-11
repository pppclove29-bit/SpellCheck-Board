package com.typeright.keyboard.auth

import com.typeright.keyboard.api.HttpRequest
import com.typeright.keyboard.api.HttpTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/** Supabase (GoTrue) session. [expiresAtEpochSec] is the access token expiry. */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
    val userId: String,
)

/** Persistence for the session, shared by IME and host app (see DataStoreSessionStore). */
interface SessionStore {
    suspend fun load(): AuthSession?
    suspend fun save(session: AuthSession?)

    /** A stable random id used as `X-Dev-User-Id` when Supabase is not configured (backend dev mode). */
    suspend fun devUserId(): String
}

/** Supplies auth headers to API calls. */
interface AuthHeaderProvider {
    /** Headers for the next request, or null when no identity could be obtained (e.g. offline at first launch). */
    suspend fun authHeaders(): Map<String, String>?

    /** Called after a 401: force-refresh (or re-create) the session and return new headers, or null. */
    suspend fun authHeadersAfterUnauthorized(): Map<String, String>?

    /** Supabase user id (dev mode: the dev user id). Needed e.g. as the AdMob SSV userId. */
    suspend fun userId(): String?
}

/**
 * Small Supabase Auth client over GoTrue REST (no SDK):
 * - anonymous sign-in: `POST {SUPABASE_URL}/auth/v1/signup` with body `{}` and header `apikey`
 * - refresh: `POST {SUPABASE_URL}/auth/v1/token?grant_type=refresh_token` with `{"refresh_token": ...}`
 *
 * When [supabaseUrl] is blank (debug against the local dev server), no session is created and requests carry
 * `X-Dev-User-Id: <stable random id>` instead of a bearer token.
 */
class AuthSessionManager(
    supabaseUrl: String,
    private val anonKey: String,
    private val store: SessionStore,
    private val transport: HttpTransport,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AuthHeaderProvider {
    private val baseUrl = supabaseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    val isDevMode: Boolean get() = baseUrl.isEmpty()

    override suspend fun authHeaders(): Map<String, String>? = headersFor(validSession(forceRefresh = false))

    override suspend fun authHeadersAfterUnauthorized(): Map<String, String>? =
        if (isDevMode) null else headersFor(validSession(forceRefresh = true))

    override suspend fun userId(): String? =
        if (isDevMode) store.devUserId() else validSession(forceRefresh = false)?.userId

    private suspend fun headersFor(session: AuthSession?): Map<String, String>? = when {
        isDevMode -> mapOf(DEV_USER_HEADER to store.devUserId())
        session != null -> mapOf("Authorization" to "Bearer ${session.accessToken}")
        else -> null
    }

    /**
     * Returns a non-expired session: stored → refreshed → new anonymous user. Serialised so the IME and the host
     * app never refresh concurrently (a refresh token is single-use).
     */
    suspend fun validSession(forceRefresh: Boolean): AuthSession? {
        if (isDevMode) return null
        return mutex.withLock {
            val stored = store.load()
            if (stored != null && !forceRefresh && stored.expiresAtEpochSec - EXPIRY_MARGIN_SEC > nowEpochSec()) {
                return@withLock stored
            }
            if (stored != null) {
                when (val r = refresh(stored.refreshToken)) {
                    is GoTrueResult.Ok -> return@withLock r.session.also { store.save(it) }
                    is GoTrueResult.NetworkError -> return@withLock null // keep the session; retry later
                    is GoTrueResult.Rejected -> store.save(null) // refresh token revoked/used: start over
                }
            }
            when (val r = signInAnonymously()) {
                is GoTrueResult.Ok -> r.session.also { store.save(it) }
                else -> null
            }
        }
    }

    suspend fun signOut() {
        mutex.withLock { store.save(null) }
    }

    private suspend fun signInAnonymously(): GoTrueResult =
        call("$baseUrl/auth/v1/signup", "{}")

    private suspend fun refresh(refreshToken: String): GoTrueResult =
        call(
            "$baseUrl/auth/v1/token?grant_type=refresh_token",
            buildJsonObject { put("refresh_token", JsonPrimitive(refreshToken)) }.toString(),
        )

    private suspend fun call(url: String, body: String): GoTrueResult {
        val response = try {
            runInterruptible(ioDispatcher) {
                transport.execute(
                    HttpRequest(
                        method = "POST",
                        url = url,
                        headers = mapOf("apikey" to anonKey, "Authorization" to "Bearer $anonKey"),
                        body = body,
                        connectTimeoutMs = 5_000,
                        readTimeoutMs = 10_000,
                    ),
                )
            }
        } catch (e: IOException) {
            return GoTrueResult.NetworkError
        } catch (e: RuntimeException) {
            return GoTrueResult.NetworkError
        }
        if (response.code in 500..599 || response.code == 429) return GoTrueResult.NetworkError
        if (response.code !in 200..299) return GoTrueResult.Rejected
        return parseSession(response.body)?.let { GoTrueResult.Ok(it) } ?: GoTrueResult.Rejected
    }

    internal fun parseSession(body: String): AuthSession? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        val access = o.str("access_token") ?: return null
        val refresh = o.str("refresh_token") ?: return null
        val userId = (o["user"] as? JsonObject)?.str("id") ?: return null
        val expiresAt = (o["expires_at"] as? JsonPrimitive)?.longOrNull
            ?: (o["expires_in"] as? JsonPrimitive)?.longOrNull?.let { nowEpochSec() + it }
            ?: (nowEpochSec() + DEFAULT_TTL_SEC)
        AuthSession(access, refresh, expiresAt, userId)
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private sealed interface GoTrueResult {
        data class Ok(val session: AuthSession) : GoTrueResult
        data object NetworkError : GoTrueResult
        data object Rejected : GoTrueResult
    }

    companion object {
        const val DEV_USER_HEADER = "X-Dev-User-Id"
        private const val EXPIRY_MARGIN_SEC = 60L
        private const val DEFAULT_TTL_SEC = 3600L
    }
}
