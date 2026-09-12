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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntentTraceApiClientTest {
    @Test
    fun `로그인 확인은 내 세션 API에 토큰을 보내고 서버가 확인한 GitHub 계정을 반환한다`() {
        val method = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        withServer(path = "/api/v1/me/sessions", handler = { exchange ->
            method.set(exchange.requestMethod)
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val body = """{"actor":{"subject":"github:42","login":"developer"},"authentication":"LOCAL_SESSION","sessions":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }) { server ->
            val endpoint = IntentTraceServer.parse("http://127.0.0.1:${server.address.port}")
            assertEquals("developer", IntentTraceApiClient().checkLogin(endpoint, token))
        }
        assertEquals("GET", method.get())
        assertEquals("Bearer $token", authorization.get())
    }

    @Test
    fun `로그인 확인에서 세션이 없거나 형식이 틀리면 서버에 요청하지 않는다`() {
        val calls = AtomicInteger()
        withServer(path = "/", handler = { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }) { server ->
            val endpoint = IntentTraceServer.parse("http://127.0.0.1:${server.address.port}")
            for (session in listOf(null, "ghu_not-an-intent-trace-session")) {
                assertFailsWith<IntentTraceUsageException> { IntentTraceApiClient().checkLogin(endpoint, session) }
            }
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun `로그인 실패는 기록 조회 오류와 구분하고 응답 원문을 숨긴다`() {
        for ((status, message) in mapOf(
            401 to "세션이 만료됐습니다. GitHub에 다시 로그인하고 새 세션을 연결해 주세요.",
            403 to "로그인 정보를 확인할 권한이 없습니다.",
            404 to "로그인 확인 API를 찾을 수 없습니다. 서버 버전을 확인해 주세요.",
            200 to "IntentTrace 조회 응답 형식을 확인할 수 없습니다.",
        )) {
            withServer(path = "/api/v1/me/sessions", handler = { exchange ->
                val body = """{"error":"test-private-response-marker"}""".toByteArray()
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }) { server ->
                val endpoint = IntentTraceServer.parse("http://127.0.0.1:${server.address.port}")
                val error = assertFailsWith<IntentTraceClientException> { IntentTraceApiClient().checkLogin(endpoint, token) }
                assertEquals(message, error.message)
                assertFalse(error.stackTraceToString().contains("test-private-response-marker"))
            }
        }
    }

    @Test
    fun `연결 확인은 세션 없이 health를 조회하고 UP 상태만 성공으로 처리한다`() {
        val authorization = AtomicReference<String>()
        for (status in listOf("UP", "DOWN")) {
            withServer(
                path = "/actuator/health",
                handler = { exchange ->
                    authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                    val response = """{"status":"$status"}""".toByteArray()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                },
            ) { server ->
                val target = IntentTraceServer.parse("http://127.0.0.1:${server.address.port}")
                if (status == "UP") {
                    IntentTraceApiClient().checkConnection(target)
                } else {
                    val exception = assertFailsWith<IntentTraceClientException> { IntentTraceApiClient().checkConnection(target) }
                    assertEquals("IntentTrace 서버가 정상 상태(UP)가 아닙니다.", exception.message)
                }
                assertNull(authorization.get())
            }
        }
    }

    @Test
    fun `health 거부는 로그인 만료로 표시하지 않고 응답 본문이나 redirect를 사용하지 않는다`() {
        for (status in listOf(401, 302, 503)) {
            val redirectedRequests = AtomicInteger()
            withServer(
                path = "/actuator/health",
                handler = { exchange ->
                    exchange.responseHeaders.add("Location", "/redirected")
                    val body = "test-private-response-marker".toByteArray()
                    exchange.sendResponseHeaders(status, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                },
            ) { server ->
                server.createContext("/redirected") { exchange ->
                    redirectedRequests.incrementAndGet()
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
                val exception = assertFailsWith<IntentTraceClientException> {
                    IntentTraceApiClient().checkConnection(IntentTraceServer.parse("http://127.0.0.1:${server.address.port}"))
                }
                assertEquals("IntentTrace 서버 상태 확인 요청이 거부됐습니다. HTTP $status", exception.message)
                assertEquals(0, redirectedRequests.get())
                assertFalse(exception.stackTraceToString().contains("test-private-response-marker"))
            }
        }
    }

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

    @Test
    fun `session 폐기는 DELETE와 bearer token을 보내고 이미 만료된 session도 완료로 처리한다`() {
        for (status in listOf(204, 401)) {
            val method = AtomicReference<String>()
            val authorization = AtomicReference<String>()
            withServer(
                path = "/api/v1/session",
                handler = { exchange ->
                    method.set(exchange.requestMethod)
                    authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                    exchange.sendResponseHeaders(status, -1)
                    exchange.close()
                },
            ) { server ->
                IntentTraceApiClient().revokeSession(
                    IntentTraceServer.parse("http://127.0.0.1:${server.address.port}"),
                    token,
                )
            }

            assertEquals("DELETE", method.get())
            assertEquals("Bearer $token", authorization.get())
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
                assertEquals("IntentTrace 서버의 응답 대기 시간을 초과했습니다.", exception.message)
            } finally {
                releaseBody.countDown()
            }
        }
    }

    @Test
    fun `오류는 상태 코드로 안내하고 redirect를 따라가지 않는다`() {
        val messages = mapOf(
            401 to "세션이 만료됐습니다. GitHub에 다시 로그인하고 새 세션을 연결해 주세요.",
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
    fun `호출 제한은 대기 시간을 안내하고 오류 본문 노출이나 자동 재시도를 하지 않는다`() {
        val unknown = "대기 시간을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
        for ((retryAfter, guidance) in listOf(
            "120" to "120초 후 다시 시도해 주세요.",
            "0" to "0초 후 다시 시도해 주세요.",
            null to unknown,
            "-1" to unknown,
            "999999999999999999999" to unknown,
            token to unknown,
        )) {
            val calls = AtomicInteger()
            withServer(path = "/", handler = { exchange ->
                calls.incrementAndGet()
                retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
                val body = "test-private-response-marker $token".toByteArray()
                exchange.sendResponseHeaders(429, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }) { server ->
                val endpoint = IntentTraceServer.parse("http://127.0.0.1:${server.address.port}")
                val api = IntentTraceApiClient()
                val operations = listOf<() -> Unit>(
                    { lookup(server) },
                    { api.checkConnection(endpoint) },
                    { api.checkLogin(endpoint, token) },
                    { api.revokeSession(endpoint, token) },
                )
                operations.forEachIndexed { index, operation ->
                    val error = assertFailsWith<IntentTraceClientException> { operation() }
                    assertEquals("호출 제한에 도달했습니다. $guidance", error.message)
                    assertFalse(error.stackTraceToString().contains(token))
                    assertFalse(error.stackTraceToString().contains("test-private-response-marker"))
                    assertEquals(index + 1, calls.get())
                }
            }
        }
    }

    @Test
    fun `1MB를 넘는 한글 기록도 단건과 현재 줄 조회에서 끝까지 읽는다`() {
        val id = "3efecb93-18c5-4af7-84a7-f830d0b63281"
        val revision = "a".repeat(40)
        val summary = "가".repeat(1000)
        val detail = "나".repeat(2000)
        val symbol = "함".repeat(500)
        val decisions = List(20) {
            """{"summary":"$summary","rationale":"$detail","source":"STATED_BY_USER"}"""
        }.joinToString(",")
        val anchors = List(100) {
            """{"relativePath":"src/App.kt","symbolName":"$symbol","startLine":1,"endLine":1}"""
        }.joinToString(",")
        val verifications = List(50) {
            """{"command":"$detail","exitCode":0,"summary":"$detail","current":true}"""
        }.joinToString(",")
        val questions = List(50) { "\"$summary\"" }.joinToString(",")
        val recordJson = """
            {"id":"$id","repositoryKey":"team/repository","targetRevision":"$revision",
             "title":"큰 기록","requestSummary":"응답 크기를 확인한다.","status":"PUBLISHED",
             "createdBy":{"login":"developer"},"decisions":[$decisions],"codeAnchors":[$anchors],
             "verifications":[$verifications],"openQuestions":[$questions]}
        """.trimIndent()
        assertTrue(recordJson.toByteArray(StandardCharsets.UTF_8).size > 1_000_000)

        withServer(
            path = "/api/v1/change-records",
            handler = { exchange ->
                val json = if (exchange.requestURI.path.endsWith("/lookup")) "[$recordJson]" else recordJson
                val body = json.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            },
        ) { server ->
            val endpoint = IntentTraceServer.parse("http://127.0.0.1:" + server.address.port)
            val record = IntentTraceApiClient().record(endpoint, token, id)

            assertEquals(record, lookup(server).single())
            assertEquals(50, record.verifications.size)
            assertEquals(detail, record.verifications.last().summary)
            assertEquals(summary, record.openQuestions.last())
        }
    }

    @Test
    fun `4MiB 응답까지 읽고 한 바이트라도 넘으면 JSON을 해석하지 않는다`() {
        for (size in listOf(4 * 1024 * 1024, 4 * 1024 * 1024 + 1)) {
            withServer(
                handler = { exchange ->
                    val body = ("[]" + " ".repeat(size - 2)).toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                },
            ) { server ->
                if (size == 4 * 1024 * 1024) {
                    assertEquals(emptyList(), lookup(server))
                } else {
                    val exception = assertFailsWith<IntentTraceClientException> { lookup(server) }
                    assertEquals("IntentTrace 조회 응답이 허용 크기를 초과했습니다.", exception.message)
                }
            }
        }
    }

    private fun lookup(server: HttpServer): List<ChangeIntentRecord> = IntentTraceApiClient().lookup(
        server = IntentTraceServer.parse("http://127.0.0.1:${server.address.port}"),
        sessionToken = token,
        lookup = LineLookup("team/repository", "a".repeat(40), "src/main/App.kt", 12),
    )

    private fun withServer(handler: HttpHandler, path: String = "/api/v1/change-records/lookup", test: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(path, handler)
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
