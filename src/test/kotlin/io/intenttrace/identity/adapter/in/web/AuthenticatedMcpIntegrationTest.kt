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
import io.intenttrace.record.application.ChangeRecordFacade
import io.intenttrace.record.application.ConfirmChangeRecordCommand
import io.intenttrace.record.application.PublishChangeRecordCommand
import io.intenttrace.record.domain.ChangeRecordStatus
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
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
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val facade: ChangeRecordFacade,
) {
    private val initialize = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
          "protocolVersion":"2025-06-18","capabilities":{},
          "clientInfo":{"name":"intent-trace-test","version":"1.0"}
        }}
    """.trimIndent()

    @Test
    fun `MCP는 인증된 사용자만 초기화하고 목록 기본값과 전체 revision 계약을 적용한다`() {
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

        val listed = mockMvc.post("/mcp") {
            header("Authorization", "Bearer ghu_user-token")
            header("Mcp-Session-Id", sessionId)
            contentType = MediaType.APPLICATION_JSON
            header("Accept", "application/json, text/event-stream")
            content = """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"list_change_records","arguments":{"repositoryKey":"acme/intent-trace"}
                }}
            """.trimIndent()
        }.andExpect {
            status { isOk() }
        }.andReturn()
        val data = listed.response.contentAsString.lineSequence().first { it.startsWith("data:") }.removePrefix("data:")
        val result = objectMapper.readTree(data).get("result")
        assertEquals(false, result.get("isError").booleanValue())
        val page = result.get("structuredContent")
        assertEquals(0, page.get("items").size())
        assertEquals(0, page.get("page").intValue())
        assertEquals(20, page.get("size").intValue())
        assertEquals(false, page.get("hasNext").booleanValue())
    }

    @Test
    fun `MCP 대체 도구는 기존 작성자와 버전 검사를 거쳐 공개 기록을 대체한다`() {
        val actor = ActorIdentity.github(42, "lim")
        val repository = "acme/mcp-supersede-${UUID.randomUUID()}"
        val digest = "a".repeat(64)
        fun publishedRecord() = facade.create(
            CreateChangeRecordRequest(
                requestId = UUID.randomUUID().toString(),
                repositoryKey = repository,
                snapshotDigest = digest,
                title = "MCP 기록 대체",
                requestSummary = "공개 기록의 본문을 유지하고 후속 기록을 연결한다.",
                decisions = listOf(DecisionRequest("기존 대체 서비스를 사용한다.", null, PurposeSource.STATED_BY_USER)),
                codeAnchors = listOf(CodeAnchorRequest("src/App.kt", "App", 1, 1, digest)),
            ).toCommand(),
            actor,
        ).let { draft ->
            facade.confirm(ConfirmChangeRecordCommand(draft.id, draft.version, "b".repeat(40), digest), actor)
        }.let { confirmed ->
            facade.publish(PublishChangeRecordCommand(confirmed.id, confirmed.version, digest), actor)
        }.let { published ->
            facade.get(published.id)
        }
        val original = publishedRecord()
        val replacement = publishedRecord()

        fun callSupersede(accessToken: String): JsonNode {
            val session = mockMvc.post("/mcp") {
                header("Authorization", "Bearer $accessToken")
                contentType = MediaType.APPLICATION_JSON
                header("Accept", "application/json, text/event-stream")
                content = initialize
            }.andExpect { status { isOk() } }.andReturn().response.getHeader("Mcp-Session-Id")
            assertNotNull(session)
            val response = mockMvc.post("/mcp") {
                header("Authorization", "Bearer $accessToken")
                header("Mcp-Session-Id", session)
                contentType = MediaType.APPLICATION_JSON
                header("Accept", "application/json, text/event-stream")
                content = """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"supersede_change_record","arguments":{
                        "recordId":"${original.id}","expectedVersion":${original.version},
                        "replacementRecordId":"${replacement.id}"
                      }
                    }}
                """.trimIndent()
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString
            val data = response.lineSequence().first { it.startsWith("data:") }.removePrefix("data:")
            return objectMapper.readTree(data).get("result")
        }

        assertTrue(callSupersede("ghu_other-user-token").get("isError").booleanValue())
        assertEquals(original, facade.get(original.id))

        val result = callSupersede("ghu_user-token")
        assertEquals(false, result.get("isError").booleanValue())
        val updated = result.get("structuredContent")
        assertEquals("SUPERSEDED", updated.get("status").stringValue())
        assertEquals(replacement.id.toString(), updated.get("supersededBy").stringValue())
        assertEquals(original.version + 1, updated.get("version").longValue())
        assertEquals(
            original.copy(status = ChangeRecordStatus.SUPERSEDED, supersededBy = replacement.id, version = original.version + 1),
            facade.get(original.id),
        )
        assertTrue(callSupersede("ghu_user-token").get("isError").booleanValue())
        assertEquals(replacement, facade.get(replacement.id))
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
            override fun authenticate(accessToken: String): ActorIdentity =
                if (accessToken == "ghu_other-user-token") ActorIdentity.github(84, "teammate") else ActorIdentity.github(42, "lim")

            override fun repositoryRole(
                accessToken: String,
                repository: GitHubRepository,
            ): RepositoryRole = RepositoryRole.MAINTAINER
        }
    }
}
