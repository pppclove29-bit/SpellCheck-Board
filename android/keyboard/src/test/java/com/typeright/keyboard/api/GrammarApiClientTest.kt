package com.typeright.keyboard.api

import com.typeright.keyboard.auth.AuthHeaderProvider
import com.typeright.keyboard.rules.CorrectionSource
import com.typeright.keyboard.rules.FeedbackMode
import com.typeright.keyboard.rules.SuggestionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

internal class FakeTransport(vararg responses: HttpResponse) : HttpTransport {
    private val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<HttpRequest>()
    var failWith: IOException? = null

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        failWith?.let { throw it }
        return queue.removeFirst()
    }
}

private class FakeAuth(
    private val headers: Map<String, String>?,
    private val refreshed: Map<String, String>? = null,
) : AuthHeaderProvider {
    var unauthorizedCalls = 0
    override suspend fun authHeaders() = headers
    override suspend fun authHeadersAfterUnauthorized(): Map<String, String>? {
        unauthorizedCalls++
        return refreshed
    }
    override suspend fun userId() = "user-1"
}

@OptIn(ExperimentalCoroutinesApi::class)
class GrammarApiClientTest {
    private val text = "오늘 진짜 어의가 없네"
    private val v4Body = """
        {"original_text":"$text","has_error":true,"corrected_text":"오늘 진짜 어이가 없네",
         "wit_feedback":"어의는 조선시대 궁궐 의사입니다.",
         "suggestions":[
           {"offset":6,"length":2,"original_word":"어의","suggested_word":"어이","type":"spelling","reason":"r","source":"rule"},
           {"offset":0,"length":2,"original_word":"XX","suggested_word":"YY","type":"grammar","reason":"bad span","source":"ai"}],
         "engine":"hybrid","ai_status":"used",
         "quota":{"is_pro":false,"limit":5,"used":1,"bonus":0,"remaining":4}}
    """.trimIndent()

    @Test
    fun grammarCheckSendsV4RequestAndParsesResponse() = runTest {
        val transport = FakeTransport(HttpResponse(200, v4Body))
        val client = GrammarApiClient(
            "http://10.0.2.2:8790/",
            FakeAuth(mapOf("X-Dev-User-Id" to "dev-1")),
            transport,
            UnconfinedTestDispatcher(testScheduler),
        )
        val result = client.grammarCheck(text, FeedbackMode.POLICE) as ApiResult.Success

        val req = transport.requests.single()
        assertEquals("POST", req.method)
        assertEquals("http://10.0.2.2:8790/v1/grammar-check", req.url)
        assertEquals("dev-1", req.headers["X-Dev-User-Id"])
        assertEquals("android", req.headers["X-Client-Platform"])
        val body = Json.parseToJsonElement(req.body!!).jsonObject
        assertEquals(text, body.getValue("text").jsonPrimitive.content)
        assertEquals("police", body.getValue("mode").jsonPrimitive.content)

        val r = result.value
        assertEquals(1, r.suggestions.size) // the span that doesn't match the sent text is dropped
        val s = r.suggestions.single()
        assertEquals(6, s.offset)
        assertEquals("어이", s.suggestedWord)
        assertEquals(SuggestionType.SPELLING, s.type)
        assertEquals(CorrectionSource.RULE, s.source)
        assertEquals(AiStatus.USED, r.aiStatus)
        assertEquals(4, r.quota!!.remaining)
        assertEquals("어의는 조선시대 궁궐 의사입니다.", r.witFeedback)
        assertTrue(r.hasError)
    }

    @Test
    fun quotaExceededAndNullFeedback() = runTest {
        val body = """{"original_text":"$text","has_error":false,"corrected_text":"$text","wit_feedback":null,
            "suggestions":[],"engine":"rule","ai_status":"quota_exceeded",
            "quota":{"is_pro":false,"limit":5,"used":5,"bonus":0,"remaining":0}}"""
        val client = GrammarApiClient("http://h", FakeAuth(mapOf("Authorization" to "Bearer t")),
            FakeTransport(HttpResponse(200, body)), UnconfinedTestDispatcher(testScheduler))
        val r = (client.grammarCheck(text, FeedbackMode.SPICY_WIT) as ApiResult.Success).value
        assertEquals(AiStatus.QUOTA_EXCEEDED, r.aiStatus)
        assertEquals(null, r.witFeedback)
        assertEquals(0, r.quota!!.remaining)
    }

    @Test
    fun retriesOnceAfter401WithRefreshedHeaders() = runTest {
        val transport = FakeTransport(HttpResponse(401, """{"error":{"code":"UNAUTHORIZED","message":"x"}}"""), HttpResponse(200, v4Body))
        val auth = FakeAuth(mapOf("Authorization" to "Bearer old"), mapOf("Authorization" to "Bearer new"))
        val client = GrammarApiClient("http://h", auth, transport, UnconfinedTestDispatcher(testScheduler))
        assertTrue(client.grammarCheck(text, FeedbackMode.GENTLE) is ApiResult.Success)
        assertEquals(1, auth.unauthorizedCalls)
        assertEquals(listOf("Bearer old", "Bearer new"), transport.requests.map { it.headers["Authorization"] })
    }

    @Test
    fun networkErrorsAreReturnedNotThrown() = runTest {
        val transport = FakeTransport().apply { failWith = IOException("timeout") }
        val client = GrammarApiClient("http://h", FakeAuth(mapOf("X-Dev-User-Id" to "d")), transport, UnconfinedTestDispatcher(testScheduler))
        val r = client.grammarCheck(text, FeedbackMode.SPICY_WIT)
        assertEquals(ApiResult.Failure.Kind.NETWORK, (r as ApiResult.Failure).kind)
    }

    @Test
    fun noIdentityMeansNoRequest() = runTest {
        val transport = FakeTransport()
        val client = GrammarApiClient("http://h", FakeAuth(null), transport, UnconfinedTestDispatcher(testScheduler))
        val r = client.grammarCheck(text, FeedbackMode.SPICY_WIT)
        assertEquals(ApiResult.Failure.Kind.UNAUTHENTICATED, (r as ApiResult.Failure).kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun httpErrorCodeIsParsed() = runTest {
        val transport = FakeTransport(HttpResponse(400, """{"error":{"code":"INVALID_REQUEST","message":"too long"}}"""))
        val client = GrammarApiClient("http://h", FakeAuth(mapOf("X-Dev-User-Id" to "d")), transport, UnconfinedTestDispatcher(testScheduler))
        val r = client.grammarCheck(text, FeedbackMode.SPICY_WIT) as ApiResult.Failure
        assertEquals(ApiResult.Failure.Kind.HTTP, r.kind)
        assertEquals(400, r.httpCode)
        assertEquals("INVALID_REQUEST", r.message)
    }

    @Test
    fun meIsAGetAndParses() = runTest {
        val transport = FakeTransport(
            HttpResponse(200, """{"user_id":"uuid-1","is_pro":true,"quota":{"is_pro":true,"limit":300,"used":2,"bonus":3,"remaining":301}}"""),
        )
        val client = GrammarApiClient("http://h", FakeAuth(mapOf("X-Dev-User-Id" to "d")), transport, UnconfinedTestDispatcher(testScheduler))
        val me = (client.me() as ApiResult.Success).value
        assertEquals("GET", transport.requests.single().method)
        assertEquals("http://h/v1/me", transport.requests.single().url)
        assertEquals(null, transport.requests.single().body)
        assertEquals("uuid-1", me.userId)
        assertTrue(me.isPro)
        assertEquals(301, me.quota!!.remaining)
    }

    @Test
    fun overLongTextIsRejectedLocally() = runTest {
        val transport = FakeTransport()
        val client = GrammarApiClient("http://h", FakeAuth(mapOf("X-Dev-User-Id" to "d")), transport, UnconfinedTestDispatcher(testScheduler))
        val r = client.grammarCheck("가".repeat(1001), FeedbackMode.SPICY_WIT)
        assertEquals(ApiResult.Failure.Kind.INVALID_REQUEST, (r as ApiResult.Failure).kind)
        assertTrue(transport.requests.isEmpty())
    }
}
