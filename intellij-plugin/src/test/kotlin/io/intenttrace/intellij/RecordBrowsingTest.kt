package io.intenttrace.intellij

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RecordBrowsingTest {
    @Test
    fun `기록함과 상세 조회는 같은 세션으로 호출하고 선택 조건을 전송한다`() {
        val requests = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/change-records") { exchange ->
                requests.add(exchange.requestURI.toString())
                tokens.add(exchange.requestHeaders.getFirst("Authorization"))
                val body = if (exchange.requestURI.path.endsWith(id)) recordJson else """
                    {"items":[{"id":"$id","title":"비공개 기록","status":"DRAFT","targetRevision":null,
                    "createdBy":{"login":"developer"},"createdAt":"2026-08-30T00:00:00Z"}],"page":2,"size":20,"hasNext":false}
                """.trimIndent()
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val api = IntentTraceApiClient()
            val endpoint = IntentTraceServer.parse("http://127.0.0.1:${server.address.port}")
            val query = RecordListQuery("team/repository", RecordListScope.MY_DRAFTS, "src/한 글#?.kt", "DRAFT", 2)
            val page = api.list(endpoint, token, query)
            val record = api.record(endpoint, token, id)

            assertEquals(2, page.page)
            assertFalse(page.hasNext)
            assertNull(page.items.single().targetRevision)
            assertEquals("team/repository", record.repositoryKey)
            assertEquals(replacement, record.supersededBy)
            assertContains(requests.first(), "scope=MY_DRAFTS")
            assertContains(requests.first(), "path=src%2F%ED%95%9C+%EA%B8%80%23%3F.kt&status=DRAFT&page=2&size=20")
            assertEquals("/api/v1/change-records/$id", requests.last())
            assertEquals(listOf("Bearer $token", "Bearer $token"), tokens)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `과거 기록은 전체 커밋과 당시 스냅샷을 표시하고 코드 링크를 인코딩한다`() {
        val record = IntentTraceResponseParser.parseRecord(recordJson)
        val output = IntentTraceTextRenderer.renderHistory(record)
        assertContains(output, revision)
        assertContains(output, "현재 편집 중인 코드의 검증이 아닙니다.")
        assertContains(output, "기록 스냅샷과 일치")
        assertContains(output, "대체 기록: $replacement")
        assertEquals("https://github.com/team/repository/commit/$revision", GitHubEvidenceLinks.commit(record).toString())
        val code = GitHubEvidenceLinks.code(record, ChangeCodeAnchor("src/한 글#?.kt", 10, 12))
        assertEquals("github.com", code.host)
        assertEquals("/team/repository/blob/$revision/src/한 글#?.kt", code.path)
        assertNull(code.query)
        assertEquals("L10-L12", code.fragment)
        assertContains(code.toASCIIString(), "%23%3F.kt#L10-L12")
    }

    @Test
    fun `브라우저 링크에 브랜치명이나 경로 탈출을 넣지 않는다`() {
        val record = IntentTraceResponseParser.parseRecord(recordJson)
        assertFailsWith<IntentTraceUsageException> { GitHubEvidenceLinks.commit(record.copy(targetRevision = "main")) }
        assertFailsWith<IntentTraceUsageException> { GitHubEvidenceLinks.code(record, ChangeCodeAnchor("../App.kt", 1, 1)) }
        assertFailsWith<IllegalArgumentException> { IntentTraceServer.parse(null).recordUri("../auth/github/start") }
    }

    companion object {
        private val id = UUID.randomUUID().toString()
        private val replacement = UUID.randomUUID().toString()
        private val revision = "a".repeat(40)
        private val token = "its_${"A".repeat(43)}"
        private val recordJson = """
            {"id":"$id","repositoryKey":"team/repository","targetRevision":"$revision","supersededBy":"$replacement",
             "title":"파일 이력","requestSummary":"과거 의도를 확인한다.","status":"SUPERSEDED","createdBy":{"login":"developer"},
             "decisions":[],"codeAnchors":[],"openQuestions":[],
             "verifications":[{"command":"./gradlew test","exitCode":0,"summary":"통과","current":true}]}
        """.trimIndent()
    }
}
