package com.typeright.keyboard.api

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** JSON body (sent with `Content-Type: application/json; charset=utf-8`), or null for none. */
    val body: String? = null,
    val connectTimeoutMs: Int = 3_000,
    val readTimeoutMs: Int = 6_000,
)

data class HttpResponse(val code: Int, val body: String)

/** Minimal blocking HTTP abstraction (swappable in tests). Callers must invoke it off the main thread. */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

class UrlConnectionTransport : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = request.connectTimeoutMs
            conn.readTimeout = request.readTimeoutMs
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val body = request.body
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
