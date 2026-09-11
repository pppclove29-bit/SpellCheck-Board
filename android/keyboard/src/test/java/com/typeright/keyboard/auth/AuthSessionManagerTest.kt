package com.typeright.keyboard.auth

import com.typeright.keyboard.api.HttpRequest
import com.typeright.keyboard.api.HttpResponse
import com.typeright.keyboard.api.HttpTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

private class MemoryStore(var session: AuthSession? = null) : SessionStore {
    override suspend fun load() = session
    override suspend fun save(session: AuthSession?) {
        this.session = session
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
        """{"access_token":"A1","token_type":"bearer","expires_in":3600,"expires_at":5000,"refresh_token":"R1","user":{"id":"user-1"}}"""

    private fun manager(store: SessionStore, transport: HttpTransport, supabaseUrl: String = url, now: Long = 1000L) =
        AuthSessionManager(supabaseUrl, "anon-key", store, transport, { now }, UnconfinedTestDispatcher())

    @Test
    fun devModeSendsDevUserHeaderWithoutNetwork() = runTest {
        val transport = ScriptedTransport()
        val m = manager(MemoryStore(), transport, supabaseUrl = "")
        assertTrue(m.isDevMode)
        assertEquals(mapOf("X-Dev-User-Id" to "dev-fixed"), m.authHeaders())
        assertEquals("dev-fixed", m.userId())
        assertNull(m.authHeadersAfterUnauthorized())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun signsInAnonymouslyWhenNoSession() = runTest {
        val store = MemoryStore()
        val transport = ScriptedTransport(HttpResponse(200, sessionJson))
        val headers = manager(store, transport).authHeaders()

        assertEquals(mapOf("Authorization" to "Bearer A1"), headers)
        val req = transport.requests.single()
        assertEquals("POST", req.method)
        assertEquals("https://proj.supabase.co/auth/v1/signup", req.url)
        assertEquals("{}", req.body)
        assertEquals("anon-key", req.headers["apikey"])
        assertEquals(AuthSession("A1", "R1", 5000, "user-1"), store.session)
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
        val store = MemoryStore(AuthSession("A0", "R0", expiresAtEpochSec = 1030, userId = "user-1"))
        val transport = ScriptedTransport(HttpResponse(200, sessionJson))
        assertEquals(mapOf("Authorization" to "Bearer A1"), manager(store, transport).authHeaders())
        val req = transport.requests.single()
        assertEquals("https://proj.supabase.co/auth/v1/token?grant_type=refresh_token", req.url)
        assertEquals("R0", Json.parseToJsonElement(req.body!!).jsonObject.getValue("refresh_token").jsonPrimitive.content)
        assertEquals("A1", store.session!!.accessToken)
    }

    @Test
    fun rejectedRefreshStartsNewAnonymousSession() = runTest {
        val store = MemoryStore(AuthSession("A0", "R0", expiresAtEpochSec = 0, userId = "old"))
        val transport = ScriptedTransport(HttpResponse(400, """{"error":"invalid_grant"}"""), HttpResponse(200, sessionJson))
        assertEquals(mapOf("Authorization" to "Bearer A1"), manager(store, transport).authHeaders())
        assertEquals(listOf("/auth/v1/token?grant_type=refresh_token", "/auth/v1/signup"), transport.requests.map { it.url.substringAfter(".co") })
        assertEquals("user-1", store.session!!.userId)
    }

    @Test
    fun networkErrorKeepsStoredSession() = runTest {
        val old = AuthSession("A0", "R0", expiresAtEpochSec = 0, userId = "old")
        val store = MemoryStore(old)
        val transport = ScriptedTransport().apply { failWith = IOException("offline") }
        assertNull(manager(store, transport).authHeaders())
        assertEquals(old, store.session)
    }

    @Test
    fun forceRefreshAfterUnauthorized() = runTest {
        val store = MemoryStore(AuthSession("A0", "R0", expiresAtEpochSec = 9000, userId = "user-1"))
        val transport = ScriptedTransport(HttpResponse(200, sessionJson))
        assertEquals(mapOf("Authorization" to "Bearer A1"), manager(store, transport).authHeadersAfterUnauthorized())
        assertTrue(transport.requests.single().url.endsWith("grant_type=refresh_token"))
    }

    @Test
    fun parsesExpiresInWhenExpiresAtMissing() {
        val m = manager(MemoryStore(), ScriptedTransport(), now = 100L)
        val s = m.parseSession("""{"access_token":"a","refresh_token":"r","expires_in":60,"user":{"id":"u"}}""")
        assertEquals(160L, s!!.expiresAtEpochSec)
        assertNull(m.parseSession("""{"access_token":"a"}"""))
    }
}
