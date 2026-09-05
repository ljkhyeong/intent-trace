package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.IntentTraceApplication
import io.intenttrace.identity.application.GitHubUserAccessGateway
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import io.intenttrace.record.adapter.`in`.mcp.IntentTraceTools
import io.intenttrace.record.adapter.`in`.web.CodeAnchorRequest
import io.intenttrace.record.adapter.`in`.web.CreateChangeRecordRequest
import io.intenttrace.record.adapter.`in`.web.DecisionRequest
import io.intenttrace.record.domain.PurposeSource
import jakarta.validation.ConstraintViolationException
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [IntentTraceApplication::class, AuthenticatedMcpIntegrationTest.AuthenticationTestConfiguration::class],
    properties = [
        "spring.datasource.url=jdbc:h2:mem:authenticated-mcp-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.h2.console.enabled=false",
    ],
)
@AutoConfigureMockMvc
class AuthenticatedMcpIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tools: IntentTraceTools,
    @Autowired private val records: io.intenttrace.record.application.ChangeRecordFacade,
) {
    @Test
    fun `MCP는 인증된 사용자만 초기화하고 잘못된 revision을 거부한다`() {
        val initialize =
            """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "initialize",
              "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "intent-trace-test", "version": "1.0"}
              }
            }
            """.trimIndent()

        mockMvc.post("/mcp") {
            contentType = MediaType.APPLICATION_JSON
            header("Accept", "application/json, text/event-stream")
            content = initialize
        }.andExpect {
            status { isUnauthorized() }
        }

        val authenticated = mockMvc.post("/mcp") {
            header("Authorization", "Bearer ghu_user-token")
            contentType = MediaType.APPLICATION_JSON
            header("Accept", "application/json, text/event-stream")
            content = initialize
        }.andExpect {
            status { isOk() }
            jsonPath("$.result.serverInfo.name") { value("intent-trace") }
        }.andReturn()

        val sessionId = authenticated.response.getHeader("Mcp-Session-Id")
        assertNotNull(sessionId)

        mockMvc.post("/mcp") {
            header("Authorization", "Bearer ghu_user-token")
            header("Mcp-Session-Id", sessionId)
            contentType = MediaType.APPLICATION_JSON
            header("Accept", "application/json, text/event-stream")
            content = """{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""
        }.andExpect {
            status { isOk() }
            content { string(containsString("sync_superseded_record_to_github_pr")) }
            content { string(containsString("revoke_all_my_sessions")) }
            content { string(containsString("check_change_record_evidence")) }
            content { string(containsString("create_successor_draft")) }
            content { string(containsString("list_pull_request_records")) }
            content { string(containsString("diagnose_connection")) }
            content { string(containsString("compare_change_record")) }
            content { string(containsString("check_publication_credentials")) }
            content { string(containsString("list_record_activities")) }
        }
        val activityRecord = records.create(CreateChangeRecordRequest(
            requestId = "mcp-activity", repositoryKey = "acme/intent-trace", snapshotDigest = "a".repeat(64),
            title = "변경 이력 조회", requestSummary = "선택 버전을 생략하고 이력을 조회한다.",
            decisions = listOf(DecisionRequest("기록과 이력을 함께 저장한다.", null, PurposeSource.STATED_BY_USER)),
            codeAnchors = listOf(CodeAnchorRequest("src/App.kt", null, 1, 2, "b".repeat(64))),
        ).toCommand(), ActorIdentity.github(42, "lim"))
        mockMvc.post("/mcp") {
            header("Authorization", "Bearer ghu_user-token"); header("Mcp-Session-Id", sessionId)
            contentType = MediaType.APPLICATION_JSON; header("Accept", "application/json, text/event-stream")
            content = """{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"list_record_activities","arguments":{"recordId":"${activityRecord.id}"}}}"""
        }.andExpect {
            status { isOk() }; content { string(containsString("\"isError\":false")) }
            content { string(containsString("CREATE")) }; content { string(containsString("AUTHOR")) }
        }
        mockMvc.post("/mcp") {
            header("Authorization", "Bearer ghu_user-token")
            header("Mcp-Session-Id", sessionId)
            contentType = MediaType.APPLICATION_JSON
            header("Accept", "application/json, text/event-stream")
            content = """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_change_records","arguments":{"repositoryKey":"acme/intent-trace"}}}"""
        }.andExpect {
            status { isOk() }
            content { string(containsString("\"isError\":false")) }
        }

        mockMvc.post("/mcp") {
            header("Authorization", "Bearer ghu_user-token"); header("Mcp-Session-Id", sessionId)
            contentType = MediaType.APPLICATION_JSON; header("Accept", "application/json, text/event-stream")
            content = """{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"find_related_change_intent","arguments":{"repositoryKey":"acme/intent-trace","revision":"${"b".repeat(40)}","path":"src/App.kt","line":1}}}"""
        }.andExpect {
            status { isOk() }; content { string(containsString("\"isError\":false")) }
            content { string(containsString("\"complete\":true")) }; content { string(containsString("\"stopReason\":null")) }
        }

        mockMvc.post("/mcp") {
            header("Authorization", "Bearer ghu_user-token")
            header("Mcp-Session-Id", sessionId)
            contentType = MediaType.APPLICATION_JSON
            header("Accept", "application/json, text/event-stream")
            content = """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"diagnose_connection","arguments":{"repositoryKey":"acme/intent-trace"}}}"""
        }.andExpect {
            status { isOk() }
            content { string(containsString("\"isError\":false")) }
            content { string(containsString("NOT_CONFIGURED")) }
        }

        mockMvc.post("/mcp") {
            header("Authorization", "Bearer ghu_user-token")
            header("Mcp-Session-Id", sessionId)
            contentType = MediaType.APPLICATION_JSON
            header("Accept", "application/json, text/event-stream")
            content =
                """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "find_change_intent",
                    "arguments": {
                      "repositoryKey": "acme/intent-trace",
                      "revision": "main",
                      "path": "src/App.kt",
                      "line": 1
                    }
                  }
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
            content { string(containsString("\"isError\":true")) }
        }
    }

    @Test
    fun `MCP 생성 입력에도 Jakarta 제약을 적용한다`() {
        val exception = assertFailsWith<ConstraintViolationException> {
            tools.create(
                CreateChangeRecordRequest(
                    requestId = "request-1",
                    repositoryKey = "acme/intent-trace",
                    snapshotDigest = "a".repeat(64),
                    title = "MCP 입력 검증",
                    requestSummary = "MCP 입력도 REST와 같은 제약을 적용한다.",
                    decisions = listOf(
                        DecisionRequest("", null, PurposeSource.STATED_BY_USER),
                    ),
                    codeAnchors = listOf(
                        CodeAnchorRequest("src/App.kt", "App", 1, 1, "b".repeat(64)),
                    ),
                ),
            )
        }

        assertTrue(exception.constraintViolations.any { it.propertyPath.toString().endsWith("summary") })
    }

    @TestConfiguration
    class AuthenticationTestConfiguration {
        @Bean
        @Primary
        fun gitHubUserAccessGateway(): GitHubUserAccessGateway = object : GitHubUserAccessGateway {
            override fun authenticate(accessToken: String): ActorIdentity = ActorIdentity.github(42, "lim")

            override fun repositoryRole(
                accessToken: String,
                repository: GitHubRepository,
            ): RepositoryRole = RepositoryRole.MAINTAINER
        }
    }
}
