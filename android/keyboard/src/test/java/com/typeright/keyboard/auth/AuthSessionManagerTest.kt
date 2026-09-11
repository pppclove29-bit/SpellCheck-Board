package com.typeright.keyboard.auth

import com.typeright.keyboard.api.HttpRequest
import com.typeright.keyboard.api.HttpResponse
import com.typeright.keyboard.api.HttpTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class MemoryStore(initial: AuthSession? = null) : SessionStore {
    private val state = MutableStateFlow(initial)
    val session: AuthSession? get() = state.value
    override val sessions = state
    override suspend fun load() = state.value
    override suspend fun save(session: AuthSession?) {
        state.value = session
    }
    override suspend fun devUserId() = "dev-fixed"
}

private class ScriptedTransport(vararg responses: HttpResponse) : HttpTransport {
    private val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<HttpRequest>()
    var failWith: IOException? = null
    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        failWith?.let { throw it }
        return queue.removeFirst()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthSessionManagerTest {
    private val url = "https://proj.supabase.co/"
    private val sessionJson =
        """{"access_token":"A1","token_type":"bearer","expires_in":3600,"expires_at":5000,"refresh_token":"R1",
            "user":{"id":"user-1","email":"me@example.com"}}"""
    private var signedOutCalls = 0

    private fun manager(store: SessionStore, transport: HttpTransport, supabaseUrl: String = url, now: Long = 1000L) =
        AuthSessionManager(supabaseUrl, "anon-key", store, transport, { now }, UnconfinedTestDispatcher(), onSignedOut = { signedOutCalls++ })

    @Test
    fun devModeSendsDevUserHeaderWithoutNetwork() = runTest {
        val transport = ScriptedTransport()
        val m = manager(MemoryStore(), transport, supabaseUrl = "")
        assertTrue(m.isDevMode)
        assertEquals(mapOf("X-Dev-User-Id" to "dev-fixed"), m.authHeaders())
        assertEquals("dev-fixed", m.userId())
        assertEquals(AuthState.Dev("dev-fixed"), m.authState.first())
        assertNull(m.authHeadersAfterUnauthorized())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun noSessionMeansSignedOutAndNoAnonymousSignIn() = runTest {
        val transport = ScriptedTransport()
        val m = manager(MemoryStore(), transport)
        assertNull(m.authHeaders())
        assertNull(m.userId())
        assertEquals(AuthState.SignedOut, m.authState.first())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun googleIdTokenIsExchangedWithRawNonce() = runTest {
        val store = MemoryStore()
        val transport = ScriptedTransport(HttpResponse(200, sessionJson))
        val m = manager(store, transport)
        val result = m.signInWithGoogle(idToken = "google-id-token", rawNonce = "raw-nonce")

        assertTrue(result is SignInResult.Success)
        val req = transport.requests.single()
        assertEquals("POST", req.method)
        assertEquals("https://proj.supabase.co/auth/v1/token?grant_type=id_token", req.url)
        assertEquals("anon-key", req.headers["apikey"])
        val body = Json.parseToJsonElement(req.body!!).jsonObject
        assertEquals("google", body.getValue("provider").jsonPrimitive.content)
        assertEquals("google-id-token", body.getValue("id_token").jsonPrimitive.content)
        assertEquals("raw-nonce", body.getValue("nonce").jsonPrimitive.content)

        assertEquals(AuthSession("A1", "R1", 5000, "user-1", "me@example.com"), store.session)
        assertEquals(AuthState.SignedIn("user-1", "me@example.com"), m.authState.first())
        assertEquals(mapOf("Authorization" to "Bearer A1"), m.authHeaders())
    }

    @Test
    fun googleSignInFailures() = runTest {
        val rejected = manager(MemoryStore(), ScriptedTransport(HttpResponse(400, """{"error":"invalid_grant"}""")))
        assertEquals(
            SignInResult.Failure(SignInResult.Failure.Reason.REJECTED),
            rejected.signInWithGoogle("t", "n"),
        )
        val offline = manager(MemoryStore(), ScriptedTransport().apply { failWith = IOException("offline") })
        assertEquals(SignInResult.Failure(SignInResult.Failure.Reason.NETWORK), offline.signInWithGoogle("t", "n"))
        val dev = manager(MemoryStore(), ScriptedTransport(), supabaseUrl = "")
        assertEquals(SignInResult.Failure(SignInResult.Failure.Reason.NOT_CONFIGURED), dev.signInWithGoogle("t", "n"))
    }

    @Test
    fun reusesValidStoredSession() = runTest {
        val store = MemoryStore(AuthSession("A0", "R0", expiresAtEpochSec = 9000, userId = "user-0"))
        val transport = ScriptedTransport()
        assertEquals(mapOf("Authorization" to "Bearer A0"), manager(store, transport).authHeaders())
        assertEquals("user-0", manager(store, transport).userId())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun refreshesExpiredSession() = runTest {
        val store = MemoryStore(AuthSession("A0", "R0", expiresAtEpochSec = 1030, userId = "user-1", email = "me@example.com"))
        val transport = ScriptedTransport(HttpResponse(200, sessionJson))
        assertEquals(mapOf("Authorization" to "Bearer A1"), manager(store, transport).authHeaders())
        val req = transport.requests.single()
        assertEquals("https://proj.supabase.co/auth/v1/token?grant_type=refresh_token", req.url)
        assertEquals("R0", Json.parseToJsonElement(req.body!!).jsonObject.getValue("refresh_token").jsonPrimitive.content)
        assertEquals("A1", store.session!!.accessToken)
        assertEquals("me@example.com", store.session!!.email)
    }

    @Test
    fun rejectedRefreshSignsOut() = runTest {
        val store = MemoryStore(AuthSession("A0", "R0", expiresAtEpochSec = 0, userId = "old"))
        val transport = ScriptedTransport(HttpResponse(400, """{"error":"invalid_grant"}"""))
        val m = manager(store, transport)
        assertNull(m.authHeaders())
        assertNull(store.session)
        assertEquals(1, transport.requests.size) // no anonymous /signup fallback
        assertEquals(1, signedOutCalls)
        assertEquals(AuthState.SignedOut, m.authState.first())
    }

    @Test
    fun networkErrorKeepsStoredSession() = runTest {
        val old = AuthSession("A0", "R0", expiresAtEpochSec = 0, userId = "old")
        val store = MemoryStore(old)
        val transport = ScriptedTransport().apply { failWith = IOException("offline") }
        assertNull(manager(store, transport).authHeaders())
        assertEquals(old, store.session)
        assertEquals(0, signedOutCalls)
    }

    @Test
    fun forceRefreshAfterUnauthorized() = runTest {
        val store = MemoryStore(AuthSession("A0", "R0", expiresAtEpochSec = 9000, userId = "user-1"))
        val transport = ScriptedTransport(HttpResponse(200, sessionJson))
        assertEquals(mapOf("Authorization" to "Bearer A1"), manager(store, transport).authHeadersAfterUnauthorized())
        assertTrue(transport.requests.single().url.endsWith("grant_type=refresh_token"))
    }

    @Test
    fun backendRejectionAndSignOutClearTheSession() = runTest {
        val store = MemoryStore(AuthSession("A0", "R0", expiresAtEpochSec = 9000, userId = "user-1"))
        val m = manager(store, ScriptedTransport())
        m.onAuthRejected()
        assertNull(store.session)
        assertEquals(1, signedOutCalls)

        store.save(AuthSession("A0", "R0", 9000, "user-1"))
        m.signOut()
        assertNull(store.session)
        assertEquals(2, signedOutCalls)
    }

    @Test
    fun parsesExpiresInAndEmail() {
        val m = manager(MemoryStore(), ScriptedTransport(), now = 100L)
        val s = m.parseSession("""{"access_token":"a","refresh_token":"r","expires_in":60,"user":{"id":"u","email":"e@x.com"}}""")
        assertEquals(160L, s!!.expiresAtEpochSec)
        assertEquals("e@x.com", s.email)
        assertNull(m.parseSession("""{"access_token":"a"}"""))
    }
}

class NonceTest {
    @Test
    fun sha256HexMatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Nonce.sha256Hex("abc"))
    }

    @Test
    fun rawNonceIsRandomHex() {
        val a = Nonce.generateRaw()
        val b = Nonce.generateRaw()
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(a != b)
        assertEquals(64, Nonce.sha256Hex(a).length)
        assertTrue(Nonce.sha256Hex(a) != a)
    }
}
