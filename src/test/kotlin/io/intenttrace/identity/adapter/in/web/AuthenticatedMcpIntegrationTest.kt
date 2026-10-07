package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.call
import io.intenttrace.identity.application.GitHubUserAccessGateway
import io.intenttrace.identity.application.GitHubUserSessionStore
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import io.intenttrace.issueTestSession
import io.intenttrace.mcpClient
import io.intenttrace.record.application.ChangeRecordFacade
import io.intenttrace.record.application.createCommand
import io.intenttrace.record.application.createPublished
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.structured
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["server.shutdown=immediate"])
@AutoConfigureMockMvc
class AuthenticatedMcpIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val facade: ChangeRecordFacade,
    @Autowired private val sessions: GitHubUserSessionStore,
    @LocalServerPort private val port: Int,
) {
    // REST·MCP는 its_ 세션만 받는다. 테스트 게이트웨이는 세션에 넣은 GitHub 토큰으로 사용자를 정한다.
    private val userSession by lazy { sessions.issueTestSession(ActorIdentity.github(42, "lim"), "ghu_user-token") }
    private val otherSession by lazy { sessions.issueTestSession(ActorIdentity.github(84, "teammate"), "ghu_other-user-token") }

    @Test
    fun `MCP는 인증된 사용자만 초기화하고 목록 기본값과 전체 revision 계약을 적용한다`() {
        mockMvc.post("/mcp").andExpect { status { isUnauthorized() } }

        val record = facade.create(createCommand("acme/intent-trace", "변경 이력 조회"), ActorIdentity.github(42, "lim"))

        mcpClient(port, userSession).use { mcp ->
            assertEquals("intent-trace", mcp.serverInfo.name())
            val tools = mcp.listTools().tools().map { it.name() }
            assertTrue(tools.containsAll(listOf(
                "sync_superseded_record_to_github_pr", "revoke_all_my_sessions", "check_change_record_evidence",
                "create_successor_draft", "list_pull_request_records", "diagnose_connection", "compare_change_record",
                "check_publication_credentials", "list_record_activities", "get_change_record_markdown", "list_record_publications",
            )), tools.toString())

            val activities = mcp.call("list_record_activities", mapOf("recordId" to record.id.toString())).succeeded()
            assertTrue(activities.toString().contains("CREATE") && activities.toString().contains("AUTHOR"), activities.toString())

            val markdown = mcp.call("get_change_record_markdown", mapOf("recordId" to record.id.toString())).succeeded()
            assertEquals("DRAFT", markdown["status"])
            assertTrue((markdown["markdown"] as String).contains("# 변경 의도: 변경 이력 조회"), markdown.toString())

            // 초안만 있으므로 기본 TEAM 범위는 비어 있다.
            val page = mcp.call("list_change_records", mapOf("repositoryKey" to "acme/intent-trace")).succeeded()
            assertEquals(emptyList<Any>(), page["items"])
            assertTrue(page.containsKey("nextCursor") && page["nextCursor"] == null, page.toString())

            val related = mcp.call("find_related_change_intent", mapOf(
                "repositoryKey" to "acme/intent-trace", "revision" to "b".repeat(40), "path" to "src/App.kt", "line" to 1,
            )).succeeded()
            assertEquals(true, related["complete"])
            assertTrue(related.containsKey("stopReason") && related["stopReason"] == null, related.toString())
            assertEquals(false, related["resumeBlocked"])

            val diagnosis = mcp.call("diagnose_connection", mapOf("repositoryKey" to "acme/intent-trace")).succeeded()
            assertTrue(diagnosis.toString().contains("NOT_CONFIGURED"), diagnosis.toString())

            val branch = mcp.call("find_change_intent", mapOf(
                "repositoryKey" to "acme/intent-trace", "revision" to "main", "path" to "src/App.kt", "line" to 1,
            ))
            assertEquals(true, branch.isError, branch.toString())
        }
    }

    @Test
    fun `MCP 기록 ID 오류는 입력값을 응답에 포함하지 않는다`() {
        val sensitiveInput = "ghu_private-marker"
        mcpClient(port, userSession).use { mcp ->
            for (tool in listOf("get_change_record", "list_record_activities", "compare_change_record", "check_change_record_evidence")) {
                val result = mcp.call(tool, mapOf("recordId" to sensitiveInput))
                assertEquals(true, result.isError, result.toString())
                assertTrue(result.content().any { it is TextContent && it.text().contains("변경 의도 기록 ID는 UUID 형식이어야 합니다.") }, result.toString())
                // content·structuredContent·_meta를 모두 담는 전체 결과에서 확인한다.
                assertFalse(result.toString().contains(sensitiveInput), result.toString())
            }
        }
    }

    @Test
    fun `MCP 대체 도구는 기존 작성자와 버전 검사를 거쳐 공개 기록을 대체한다`() {
        val actor = ActorIdentity.github(42, "lim")
        val repository = "acme/mcp-supersede-${UUID.randomUUID()}"
        val original = facade.createPublished(createCommand(repository), actor)
        val replacement = facade.createPublished(createCommand(repository), actor)

        fun supersede(session: String): CallToolResult = mcpClient(port, session).use {
            it.call("supersede_change_record", mapOf(
                "recordId" to original.id.toString(), "expectedVersion" to original.version, "replacementRecordId" to replacement.id.toString(),
            ))
        }

        assertEquals(true, supersede(otherSession).isError)
        assertEquals(original, facade.get(original.id))

        val updated = supersede(userSession).succeeded()
        assertEquals("SUPERSEDED", updated["status"])
        assertEquals(replacement.id.toString(), updated["supersededBy"])
        assertEquals(original.version + 1, (updated["version"] as Number).toLong())
        assertEquals(
            original.copy(status = ChangeRecordStatus.SUPERSEDED, supersededBy = replacement.id, version = original.version + 1),
            facade.get(original.id),
        )
        assertEquals(true, supersede(userSession).isError)
        assertEquals(replacement, facade.get(replacement.id))
    }

    @Test
    fun `MCP 도구 호출에도 Jakarta 제약을 적용한다`() {
        // Spring AI가 검증 프록시를 거쳐 도구를 호출하는지 실제 /mcp 요청으로 확인한다.
        mcpClient(port, userSession).use { mcp ->
            val created = mcp.call("create_change_record", mapOf("request" to mapOf(
                "requestId" to "mcp-validation", "repositoryKey" to "acme/intent-trace", "snapshotDigest" to "a".repeat(64),
                "title" to "MCP 입력 검증", "requestSummary" to "MCP 입력도 REST와 같은 제약을 적용한다.",
                "decisions" to listOf(mapOf("summary" to "", "source" to "STATED_BY_USER")),
                "codeAnchors" to listOf(mapOf("relativePath" to "src/App.kt", "startLine" to 1, "endLine" to 1, "contentHash" to "b".repeat(64))),
            )))
            assertEquals(true, created.isError, created.toString())
            assertTrue(created.toString().contains("summary"), created.toString())

            val confirmed = mcp.call("confirm_change_record", mapOf(
                "recordId" to UUID.randomUUID().toString(), "expectedVersion" to 0, "immutableRevision" to "main",
                "currentSnapshotDigest" to "a".repeat(64),
            ))
            assertEquals(true, confirmed.isError, confirmed.toString())
            assertTrue(confirmed.toString().contains("immutableRevision"), confirmed.toString())
        }
    }

    private fun CallToolResult.succeeded(): Map<*, *> {
        assertEquals(false, isError, toString())
        return structured
    }

    @TestConfiguration
    class AuthenticationTestConfiguration {
        @Bean
        @Primary
        fun gitHubUserAccessGateway(): GitHubUserAccessGateway = object : GitHubUserAccessGateway {
            override fun authenticate(accessToken: String): ActorIdentity =
                if (accessToken == "ghu_other-user-token") ActorIdentity.github(84, "teammate") else ActorIdentity.github(42, "lim")

            override fun repositoryRole(
                accessToken: String,
                actor: ActorIdentity,
                repository: GitHubRepository,
            ): RepositoryRole = RepositoryRole.MAINTAINER
        }
    }
}
