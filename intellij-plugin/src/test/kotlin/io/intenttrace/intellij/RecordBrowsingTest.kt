package io.intenttrace.intellij

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RecordBrowsingTest {
    @Test
    fun `기록함과 상세 조회는 같은 세션으로 호출하고 선택 조건을 전송한다`() {
        val requests = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        withHttpServer { http, endpoint ->
            http.createContext("/api/v1/change-records") { exchange ->
                requests.add(exchange.requestURI.toString())
                tokens.add(exchange.requestHeaders.getFirst("Authorization"))
                exchange.respond(200, if (exchange.requestURI.path.endsWith(id)) recordJson else """
                    {"items":[{"id":"$id","title":"비공개 기록","status":"DRAFT","targetRevision":null,
                    "createdBy":{"login":"developer"},"createdAt":"2026-08-30T00:00:00Z"}],"nextCursor":"next-page"}
                """.trimIndent())
            }
            val api = IntentTraceApiClient()
            val query = RecordListQuery(
                "team/repository", RecordListScope.MINE, "src/한 글#?.kt", "DRAFT",
                cursor = "previous+page=", keyword = "세션 & 100%_!",
            )
            val page = api.list(endpoint, token, query)
            val record = api.record(endpoint, token, id)
            assertEquals("${endpoint.baseUri}/records/$id", endpoint.webRecordUri(id).toString())

            assertEquals("next-page", page.nextCursor)
            assertNull(page.items.single().targetRevision)
            assertEquals("team/repository", record.repositoryKey)
            assertEquals(replacement, record.supersededBy)
            assertEquals(original, record.derivedFromRecordId)
            assertContains(requests.first(), "scope=MINE")
            assertContains(requests.first(), "path=src%2F%ED%95%9C+%EA%B8%80%23%3F.kt&status=DRAFT")
            assertContains(requests.first(), "cursor=previous%2Bpage%3D&q=%EC%84%B8%EC%85%98+%26+100%25_%21&limit=20")
            assertEquals("/api/v1/change-records/$id", requests.last())
            assertEquals(listOf("Bearer $token", "Bearer $token"), tokens)
        }
    }

    @Test
    fun `과거 기록은 전체 커밋과 당시 스냅샷을 표시하고 코드 링크를 인코딩한다`() {
        val record = decodeResponse<ChangeIntentRecord>(recordJson)
        val output = IntentTraceTextRenderer.renderHistory(record)
        assertNull(record.verifications.single().source)
        assertContains(output, "출처: 미확인")
        assertContains(output, revision)
        assertContains(output, "변경 전 커밋: $baseRevision")
        assertContains(output, "[변경 전] src/이전 #?.kt:3-5")
        assertContains(output, "[변경 후] src/새 파일.kt:8-10")
        assertContains(output, "현재 편집 중인 코드의 검증이 아닙니다.")
        assertContains(output, "기록 스냅샷과 일치")
        assertContains(output, "대체 기록: $replacement")
        assertContains(output, "원본 기록: $original")
        assertEquals("https://github.com/team/repository/commit/$revision", GitHubEvidenceLinks.commit(record).toString())
        val code = GitHubEvidenceLinks.code(record, ChangeCodeAnchor("src/한 글#?.kt", 10, 12))
        assertEquals("github.com", code.host)
        assertEquals("/team/repository/blob/$revision/src/한 글#?.kt", code.path)
        assertNull(code.query)
        assertEquals("L10-L12", code.fragment)
        assertContains(code.toASCIIString(), "%23%3F.kt#L10-L12")
    }

    @Test
    fun `변경 전 코드는 base 커밋을 열고 변경 후 코드는 target 커밋을 연다`() {
        val record = decodeResponse<ChangeIntentRecord>(recordJson)
        val before = GitHubEvidenceLinks.code(record, record.codeAnchors[0])
        val after = GitHubEvidenceLinks.code(record, record.codeAnchors[1])
        assertEquals("/team/repository/blob/$baseRevision/src/이전 #?.kt", before.path)
        assertEquals("L3-L5", before.fragment)
        assertNull(before.query)
        assertEquals("/team/repository/blob/$revision/src/새 파일.kt", after.path)
        assertEquals("L8-L10", after.fragment)
    }

    @Test
    fun `브라우저 링크에 브랜치명이나 경로 탈출을 넣지 않는다`() {
        val record = decodeResponse<ChangeIntentRecord>(recordJson)
        assertFailsWith<IntentTraceUsageException> { GitHubEvidenceLinks.commit(record.copy(targetRevision = "main")) }
        assertFailsWith<IntentTraceUsageException> { GitHubEvidenceLinks.code(record.copy(baseRevision = "main"), record.codeAnchors[0]) }
        assertFailsWith<IntentTraceUsageException> { GitHubEvidenceLinks.code(record.copy(baseRevision = null), record.codeAnchors[0]) }
        val draft = record.copy(targetRevision = null)
        assertEquals("/team/repository/blob/$baseRevision/src/이전 #?.kt", GitHubEvidenceLinks.code(draft, draft.codeAnchors[0]).path)
        assertFailsWith<IntentTraceUsageException> { GitHubEvidenceLinks.code(draft, draft.codeAnchors[1]) }
        assertFailsWith<IntentTraceUsageException> { GitHubEvidenceLinks.code(record, ChangeCodeAnchor("../App.kt", 1, 1)) }
        assertFailsWith<IllegalArgumentException> { IntentTraceServer.parse(null).recordUri("../auth/github/start") }
        assertFailsWith<IllegalArgumentException> { IntentTraceServer.parse(null).webRecordUri("../auth/github/start") }
    }

    companion object {
        private val id = UUID.randomUUID().toString()
        private val replacement = UUID.randomUUID().toString()
        private val original = UUID.randomUUID().toString()
        private val revision = "a".repeat(40)
        private val baseRevision = "b".repeat(40)
        private val token = "its_${"A".repeat(43)}"
        private val recordJson = """
            {"id":"$id","repositoryKey":"team/repository","baseRevision":"$baseRevision","targetRevision":"$revision","supersededBy":"$replacement","derivedFromRecordId":"$original",
             "title":"파일 이력","requestSummary":"과거 의도를 확인한다.","status":"SUPERSEDED","createdBy":{"login":"developer"},
             "decisions":[],"codeAnchors":[
               {"relativePath":"src/이전 #?.kt","startLine":3,"endLine":5,"side":"BASE"},
               {"relativePath":"src/새 파일.kt","startLine":8,"endLine":10,"side":"TARGET"}
             ],"openQuestions":[],
             "verifications":[{"command":"./gradlew test","exitCode":0,"summary":"통과","current":true}]}
        """.trimIndent()
    }
}
