package io.intenttrace.intellij

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class IntentTraceApiClientTest {
    @Test
    fun `its session과 현재 줄 문맥으로 공개 기록을 조회한다`() {
        val authorization = AtomicReference<String>()
        val requestUri = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/change-records/lookup") { exchange ->
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                requestUri.set(exchange.requestURI.toString())
                val response = "[]".toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        try {
            val token = "its_${"A".repeat(43)}"
            val records = IntentTraceApiClient().lookup(
                server = IntentTraceServer.parse("http://127.0.0.1:${server.address.port}"),
                sessionToken = token,
                lookup = LineLookup("team/repository", "a".repeat(40), "src/main/App.kt", 12),
            )

            assertEquals(emptyList(), records)
            assertEquals("Bearer $token", authorization.get())
            assertContains(requestUri.get(), "repositoryKey=team%2Frepository")
            assertContains(requestUri.get(), "path=src%2Fmain%2FApp.kt&line=12")
        } finally {
            server.stop(0)
        }
    }
}
