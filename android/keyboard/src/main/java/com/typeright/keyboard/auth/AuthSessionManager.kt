package com.typeright.keyboard.auth

import com.typeright.keyboard.api.HttpRequest
import com.typeright.keyboard.api.HttpTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
    val email: String? = null,
)

sealed interface AuthState {
    val isSignedIn: Boolean get() = this !is SignedOut

    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String?) : AuthState

    /** Local dev server without Supabase: requests carry `X-Dev-User-Id`. */
    data class Dev(val userId: String) : AuthState
}

/** Persistence for the session, shared by IME and host app (see DataStoreSessionStore). */
interface SessionStore {
    val sessions: Flow<AuthSession?>
    suspend fun load(): AuthSession?
    suspend fun save(session: AuthSession?)

    /** A stable random id used as `X-Dev-User-Id` when Supabase is not configured (backend dev mode). */
    suspend fun devUserId(): String
}

/** Supplies auth headers to API calls. */
interface AuthHeaderProvider {
    /** Headers for the next request, or null when signed out (or the session can't be refreshed right now). */
    suspend fun authHeaders(): Map<String, String>?

    /** Called after a 401: force-refresh the session and return new headers, or null. */
    suspend fun authHeadersAfterUnauthorized(): Map<String, String>?

    /** The backend still answered 401 after a refresh: the session is no longer accepted → signed-out state. */
    suspend fun onAuthRejected()

    /** Supabase user id (dev mode: the dev user id), e.g. the AdMob SSV userId. Null when signed out. */
    suspend fun userId(): String?
}

sealed interface SignInResult {
    data class Success(val session: AuthSession) : SignInResult
    data class Failure(val reason: Reason) : SignInResult {
        enum class Reason { NETWORK, REJECTED, NOT_CONFIGURED }
    }
}

/**
 * Small Supabase Auth client over GoTrue REST (no SDK). Sign-in is Google only (MVP):
 * - Google ID token exchange: `POST {SUPABASE_URL}/auth/v1/token?grant_type=id_token`
 *   body `{"provider":"google","id_token":…,"nonce":<raw nonce>}`, header `apikey`
 * - refresh: `POST {SUPABASE_URL}/auth/v1/token?grant_type=refresh_token` with `{"refresh_token": …}`
 * There is no anonymous sign-in: without a session the keyboard is signed out (on-device checks only).
 *
 * Login needs an Activity, so it happens in the host app; the IME only reads the shared session.
 * When [supabaseUrl] is blank (debug against the local dev server), requests carry `X-Dev-User-Id` instead.
 */
class AuthSessionManager(
    supabaseUrl: String,
    private val anonKey: String,
    private val store: SessionStore,
    private val transport: HttpTransport,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Invoked after the session is cleared (sign-out, revoked refresh token, backend rejection). */
    private val onSignedOut: suspend () -> Unit = {},
) : AuthHeaderProvider {
    private val baseUrl = supabaseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    val isDevMode: Boolean get() = baseUrl.isEmpty()

    val authState: Flow<AuthState> =
        if (baseUrl.isEmpty()) {
            flow { emit(AuthState.Dev(store.devUserId())) }
        } else {
            store.sessions
                .map { s -> if (s == null) AuthState.SignedOut else AuthState.SignedIn(s.userId, s.email) }
                .distinctUntilChanged()
        }

    override suspend fun authHeaders(): Map<String, String>? = headersFor(validSession(forceRefresh = false))

    override suspend fun authHeadersAfterUnauthorized(): Map<String, String>? =
        if (isDevMode) null else headersFor(validSession(forceRefresh = true))

    override suspend fun onAuthRejected() {
        if (!isDevMode) signOut()
    }

    override suspend fun userId(): String? = if (isDevMode) store.devUserId() else store.load()?.userId

    private suspend fun headersFor(session: AuthSession?): Map<String, String>? = when {
        isDevMode -> mapOf(DEV_USER_HEADER to store.devUserId())
        session != null -> mapOf("Authorization" to "Bearer ${session.accessToken}")
        else -> null
    }

    /**
     * Returns a non-expired session: stored → refreshed. A rejected refresh token signs out; a network error keeps
     * the session for later. Serialised so the IME and the host app never refresh concurrently (refresh tokens are
     * single-use).
     */
    suspend fun validSession(forceRefresh: Boolean): AuthSession? {
        if (isDevMode) return null
        var signedOut = false
        val session = mutex.withLock {
            val stored = store.load() ?: return@withLock null
            if (!forceRefresh && stored.expiresAtEpochSec - EXPIRY_MARGIN_SEC > nowEpochSec()) return@withLock stored
            when (val r = refresh(stored.refreshToken)) {
                is GoTrueResult.Ok -> r.session.copy(email = r.session.email ?: stored.email).also { store.save(it) }
                GoTrueResult.NetworkError -> null
                GoTrueResult.Rejected -> {
                    store.save(null)
                    signedOut = true
                    null
                }
            }
        }
        if (signedOut) onSignedOut()
        return session
    }

    /** Exchanges a Google ID token (obtained with SHA-256([rawNonce]) as nonce) for a Supabase session. */
    suspend fun signInWithGoogle(idToken: String, rawNonce: String): SignInResult {
        if (isDevMode) return SignInResult.Failure(SignInResult.Failure.Reason.NOT_CONFIGURED)
        val body = buildJsonObject {
            put("provider", JsonPrimitive("google"))
            put("id_token", JsonPrimitive(idToken))
            put("nonce", JsonPrimitive(rawNonce))
        }.toString()
        return mutex.withLock {
            when (val r = call("$baseUrl/auth/v1/token?grant_type=id_token", body)) {
                is GoTrueResult.Ok -> {
                    store.save(r.session)
                    SignInResult.Success(r.session)
                }
                GoTrueResult.NetworkError -> SignInResult.Failure(SignInResult.Failure.Reason.NETWORK)
                GoTrueResult.Rejected -> SignInResult.Failure(SignInResult.Failure.Reason.REJECTED)
            }
        }
    }

    suspend fun signOut() {
        mutex.withLock { store.save(null) }
        onSignedOut()
    }

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
                        headers = mapOf("apikey" to anonKey),
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
        val user = o["user"] as? JsonObject ?: return null
        val userId = user.str("id") ?: return null
        val expiresAt = (o["expires_at"] as? JsonPrimitive)?.longOrNull
            ?: (o["expires_in"] as? JsonPrimitive)?.longOrNull?.let { nowEpochSec() + it }
            ?: (nowEpochSec() + DEFAULT_TTL_SEC)
        AuthSession(access, refresh, expiresAt, userId, user.str("email"))
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
