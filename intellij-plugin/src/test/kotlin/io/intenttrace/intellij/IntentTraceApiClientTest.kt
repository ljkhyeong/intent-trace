package io.intenttrace.intellij

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class IntentTraceApiClientTest {
    @Test
    fun `its session과 현재 줄 문맥으로 공개 기록을 조회한다`() {
        val authorization = AtomicReference<String>()
        val requestUri = AtomicReference<String>()
        withServer(
            handler = { exchange ->
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                requestUri.set(exchange.requestURI.toString())
                val response = "[]".toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            },
        ) { server ->
            val records = lookup(server)

            assertEquals(emptyList(), records)
            assertEquals("Bearer $token", authorization.get())
            assertContains(requestUri.get(), "repositoryKey=team%2Frepository")
            assertContains(requestUri.get(), "path=src%2Fmain%2FApp.kt&line=12")
        }
    }

    @Test(timeout = 20_000)
    fun `헤더 이후 본문이 멈추면 읽기 제한 시간으로 실패한다`() {
        val releaseBody = CountDownLatch(1)
        withServer(
            handler = { exchange ->
                try {
                    exchange.sendResponseHeaders(200, 2)
                    releaseBody.await(15, TimeUnit.SECONDS)
                    exchange.responseBody.write("[]".toByteArray())
                } finally {
                    exchange.close()
                }
            },
        ) { server ->
            try {
                val exception = assertFailsWith<IntentTraceClientException> { lookup(server) }
                assertEquals("IntentTrace server의 응답 대기 시간을 초과했습니다.", exception.message)
            } finally {
                releaseBody.countDown()
            }
        }
    }

    @Test
    fun `오류는 상태 코드로 안내하고 redirect를 따라가지 않는다`() {
        val messages = mapOf(
            401 to "IntentTrace session이 만료됐습니다. GitHub 승인을 다시 진행해 주세요.",
            403 to "현재 GitHub 사용자는 이 기록을 조회할 권한이 없습니다.",
            404 to "해당 IntentTrace 기록을 찾을 수 없습니다.",
            503 to "IntentTrace 또는 GitHub 연동이 일시적으로 응답하지 않습니다.",
            302 to "IntentTrace 조회 요청이 거부됐습니다. HTTP 302",
        )
        for ((status, message) in messages) {
            val redirectedRequests = AtomicInteger()
            withServer(
                handler = { exchange ->
                    exchange.responseHeaders.add("Location", "/redirected")
                    val body = "test-private-response-marker".toByteArray()
                    exchange.sendResponseHeaders(status, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                },
            ) { server ->
                server.createContext("/redirected") { exchange ->
                    redirectedRequests.incrementAndGet()
                    exchange.sendResponseHeaders(200, 2)
                    exchange.responseBody.use { it.write("[]".toByteArray()) }
                }
                val exception = assertFailsWith<IntentTraceClientException> { lookup(server) }

                assertEquals(message, exception.message)
                assertEquals(0, redirectedRequests.get())
                assertFalse(exception.stackTraceToString().contains("test-private-response-marker"))
            }
        }
    }

    @Test
    fun `응답 크기 제한을 넘으면 JSON을 해석하지 않는다`() {
        withServer(
            handler = { exchange ->
                val body = ByteArray(1_000_001) { ' '.code.toByte() }
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            },
        ) { server ->
            val exception = assertFailsWith<IntentTraceClientException> { lookup(server) }
            assertEquals("IntentTrace 조회 응답이 허용 크기를 초과했습니다.", exception.message)
        }
    }

    private fun lookup(server: HttpServer): List<ChangeIntentRecord> = IntentTraceApiClient().lookup(
        server = IntentTraceServer.parse("http://127.0.0.1:${server.address.port}"),
        sessionToken = token,
        lookup = LineLookup("team/repository", "a".repeat(40), "src/main/App.kt", 12),
    )

    private fun withServer(handler: HttpHandler, test: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/change-records/lookup", handler)
            start()
        }
        try {
            test(server)
        } finally {
            server.stop(0)
        }
    }

    private val token = "its_${"A".repeat(43)}"
}
