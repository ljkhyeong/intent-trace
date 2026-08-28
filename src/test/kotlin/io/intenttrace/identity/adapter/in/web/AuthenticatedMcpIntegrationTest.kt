package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.IntentTraceApplication
import io.intenttrace.identity.application.GitHubUserAccessGateway
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
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
import kotlin.test.assertNotNull

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
) {
    @Test
    fun `MCP 초기화는 인증된 GitHub 사용자 요청만 받는다`() {
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
