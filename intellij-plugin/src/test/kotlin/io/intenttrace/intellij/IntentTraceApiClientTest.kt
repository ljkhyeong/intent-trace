package io.intenttrace.intellij

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
        withHttpServer { http, endpoint ->
            http.createContext("/api/v1/me/sessions") { exchange ->
                method.set(exchange.requestMethod)
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                exchange.respond(200, """{"actor":{"subject":"github:42","login":"developer"},"sessions":[]}""")
            }
            assertEquals("developer", IntentTraceApiClient().checkLogin(endpoint, token))
        }
        assertEquals("GET", method.get())
        assertEquals("Bearer $token", authorization.get())
    }

    @Test
    fun `로그인 확인에서 세션 형식이 틀리면 서버에 요청하지 않는다`() {
        val calls = AtomicInteger()
        withHttpServer { http, endpoint ->
            http.createContext("/") { exchange ->
                calls.incrementAndGet()
                exchange.respond(200)
            }
            assertFailsWith<IntentTraceUsageException> {
                IntentTraceApiClient().checkLogin(endpoint, "ghu_not-an-intent-trace-session")
            }
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun `로그인 실패는 기록 조회 오류와 구분하고 응답 원문을 숨긴다`() {
        for ((status, message) in mapOf(
            401 to "세션이 만료됐습니다. GitHub에 다시 로그인하고 새 세션을 연결해 주세요.",
            403 to "로그인 정보를 확인할 권한이 없습니다.",
            200 to "IntentTrace 조회 응답 형식을 확인할 수 없습니다.",
        )) {
            withHttpServer { http, endpoint ->
                http.createContext("/api/v1/me/sessions") { it.respond(status, """{"error":"test-private-response-marker"}""") }
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
            withHttpServer { http, target ->
                http.createContext("/actuator/health") { exchange ->
                    authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                    exchange.respond(200, """{"status":"$status"}""")
                }
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
            withHttpServer { http, endpoint ->
                http.createContext("/actuator/health") { exchange ->
                    exchange.responseHeaders.add("Location", "/redirected")
                    exchange.respond(status, "test-private-response-marker")
                }
                http.createContext("/redirected") { exchange ->
                    redirectedRequests.incrementAndGet()
                    exchange.respond(200)
                }
                val exception = assertFailsWith<IntentTraceClientException> { IntentTraceApiClient().checkConnection(endpoint) }
                assertEquals(if (status == 503) "IntentTrace 서버가 정상 상태(UP)가 아닙니다. HTTP 503"
                    else "IntentTrace 서버 상태 확인 요청이 거부됐습니다. HTTP $status", exception.message)
                assertEquals(0, redirectedRequests.get())
                assertFalse(exception.stackTraceToString().contains("test-private-response-marker"))
            }
        }
    }

    @Test
    fun `its session과 현재 줄 문맥으로 공개 기록을 조회한다`() {
        val authorization = AtomicReference<String>()
        val requestUri = AtomicReference<String>()
        withHttpServer { http, endpoint ->
            http.createContext(LOOKUP_PATH) { exchange ->
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                requestUri.set(exchange.requestURI.toString())
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.respond(200, EMPTY_LOOKUP)
            }
            assertEquals(ChangeIntentLookup(emptyList(), truncated = false), lookup(endpoint))
            assertEquals("Bearer $token", authorization.get())
            assertContains(requestUri.get(), "repositoryKey=team%2Frepository")
            assertContains(requestUri.get(), "path=src%2Fmain%2FApp.kt&line=12")
        }
    }

    @Test
    fun `session 폐기는 DELETE와 bearer token을 보내고 이미 만료된 session도 완료로 처리한다`() {
        for (status in listOf(200, 401)) {
            val method = AtomicReference<String>()
            val authorization = AtomicReference<String>()
            withHttpServer { http, endpoint ->
                http.createContext("/api/v1/me/sessions/current") { exchange ->
                    method.set(exchange.requestMethod)
                    authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                    exchange.respond(status)
                }
                IntentTraceApiClient().revokeSession(endpoint, token)
            }

            assertEquals("DELETE", method.get())
            assertEquals("Bearer $token", authorization.get())
        }
    }

    @Test(timeout = 20_000)
    fun `헤더 이후 본문이 멈추면 읽기 제한 시간으로 실패한다`() {
        val releaseBody = CountDownLatch(1)
        withHttpServer { http, endpoint ->
            http.createContext(LOOKUP_PATH) { exchange ->
                try {
                    exchange.sendResponseHeaders(200, EMPTY_LOOKUP.length.toLong())
                    releaseBody.await(15, TimeUnit.SECONDS)
                    exchange.responseBody.write(EMPTY_LOOKUP.toByteArray())
                } finally {
                    exchange.close()
                }
            }
            try {
                val exception = assertFailsWith<IntentTraceClientException> { lookup(endpoint) }
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
            400 to "IntentTrace가 조회 조건을 거부했습니다. 검색어 길이와 파일 경로·커밋 형식을 확인해 주세요.",
            403 to "현재 GitHub 사용자는 이 저장소의 기록을 조회할 권한이 없습니다.",
            404 to "해당 IntentTrace 기록을 찾을 수 없습니다.",
            503 to "IntentTrace 또는 GitHub 연동이 일시적으로 응답하지 않습니다.",
            302 to "IntentTrace 조회 요청이 거부됐습니다. HTTP 302",
        )
        for ((status, message) in messages) {
            val redirectedRequests = AtomicInteger()
            withHttpServer { http, endpoint ->
                http.createContext(LOOKUP_PATH) { exchange ->
                    exchange.responseHeaders.add("Location", "/redirected")
                    exchange.respond(status, "test-private-response-marker")
                }
                http.createContext("/redirected") { exchange ->
                    redirectedRequests.incrementAndGet()
                    exchange.respond(200, EMPTY_LOOKUP)
                }
                val exception = assertFailsWith<IntentTraceClientException> { lookup(endpoint) }

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
            withHttpServer { http, endpoint ->
                http.createContext("/") { exchange ->
                    calls.incrementAndGet()
                    retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
                    exchange.respond(429, "test-private-response-marker $token")
                }
                val api = IntentTraceApiClient()
                val operations = listOf<() -> Unit>(
                    { lookup(endpoint) },
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

        withHttpServer { http, endpoint ->
            http.createContext("/api/v1/change-records") { exchange ->
                exchange.respond(200, if (exchange.requestURI.path.endsWith("/lookup")) """{"items":[$recordJson],"truncated":false}""" else recordJson)
            }
            val record = IntentTraceApiClient().record(endpoint, token, id)

            assertEquals(record, lookup(endpoint).items.single())
            assertEquals(50, record.verifications.size)
            assertEquals(detail, record.verifications.last().summary)
            assertEquals(summary, record.openQuestions.last())
        }
    }

    @Test
    fun `4MiB 응답까지 읽고 한 바이트라도 넘으면 JSON을 해석하지 않는다`() {
        for (size in listOf(4 * 1024 * 1024, 4 * 1024 * 1024 + 1)) {
            withHttpServer { http, endpoint ->
                http.createContext(LOOKUP_PATH) { it.respond(200, EMPTY_LOOKUP + " ".repeat(size - EMPTY_LOOKUP.length)) }
                if (size == 4 * 1024 * 1024) {
                    assertEquals(emptyList(), lookup(endpoint).items)
                } else {
                    val exception = assertFailsWith<IntentTraceClientException> { lookup(endpoint) }
                    assertEquals("IntentTrace 조회 응답이 허용 크기를 초과했습니다.", exception.message)
                }
            }
        }
    }

    @Test
    fun `연결 진단과 이전 커밋 조회는 세션으로 요청하고 응답을 화면 모델로 읽는다`() {
        val authorization = AtomicReference<String>()
        val diagnosis = """{"repositoryKey":"team/repository","checkedAt":"2026-10-05T01:00:00Z","checks":[
            {"name":"repository_read","status":"VERIFIED","message":"GitHub 응답으로 확인했습니다."},
            {"name":"git_tree_read","status":"FAILED","message":"GitHub에서 커밋을 찾을 수 없습니다."}]}"""
        withHttpServer { http, endpoint ->
            http.createContext("/api/v1/connection-diagnostics") { exchange ->
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                exchange.respond(200, diagnosis)
            }
            val result = IntentTraceApiClient().diagnose(endpoint, token, "team/repository", null)
            assertEquals(listOf("VERIFIED", "FAILED"), result.checks.map { it.status })
        }
        assertEquals("Bearer $token", authorization.get())

        val history = """{"queryRevision":"${"a".repeat(40)}","path":"src/main/App.kt","scannedRecords":2,"nextCursor":"h1.next",
            "stopReason":"TIME_LIMIT","complete":false,"resumeBlocked":false,"failures":[{"recordId":"record-2","reason":"REVISION_NOT_FOUND"}],
            "items":[{"record":{"id":"record-1","title":"이전 기록","requestSummary":"요청","repositoryKey":"team/repository",
            "targetRevision":"${"b".repeat(40)}","status":"PUBLISHED","createdBy":{"subject":"github:1","login":"developer"},
            "createdAt":"2026-10-01T00:00:00Z","version":3},"sourceRevision":"${"b".repeat(40)}","side":"TARGET",
            "match":"ANCESTOR_MOVED_LINES","verificationAppliesToQuery":false,"sourcePath":"src/main/App.kt",
            "sourceStartLine":4,"sourceEndLine":6,"currentStartLine":10,"currentEndLine":12}]}"""
        withHttpServer { http, endpoint ->
            http.createContext("/api/v1/change-records/history") { exchange ->
                authorization.set(exchange.requestURI.rawQuery)
                exchange.respond(200, history)
            }
            val result = IntentTraceApiClient().history(endpoint, token,
                LineLookup("team/repository", "a".repeat(40), "src/main/App.kt", 12), "h1.first")
            assertEquals("ANCESTOR_MOVED_LINES", result.items.single().match)
            assertEquals(10, result.items.single().currentStartLine)
            assertEquals("REVISION_NOT_FOUND", result.failures.single().reason)
            assertEquals("h1.next", result.nextCursor)
        }
        assertContains(authorization.get(), "cursor=h1.first")
    }

    private fun lookup(endpoint: IntentTraceServer): ChangeIntentLookup = IntentTraceApiClient().lookup(
        server = endpoint,
        sessionToken = token,
        lookup = LineLookup("team/repository", "a".repeat(40), "src/main/App.kt", 12),
    )

    private val token = "its_${"A".repeat(43)}"
}

private const val LOOKUP_PATH = "/api/v1/change-records/lookup"
private const val EMPTY_LOOKUP = """{"items":[],"truncated":false}"""
