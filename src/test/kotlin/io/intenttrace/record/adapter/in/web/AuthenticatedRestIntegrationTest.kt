package io.intenttrace.record.adapter.`in`.web

import io.intenttrace.IntentTraceApplication
import io.intenttrace.identity.application.GitHubIdentityApiException
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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@SpringBootTest(
    classes = [IntentTraceApplication::class, AuthenticatedRestIntegrationTest.RestTestConfiguration::class],
    properties = [
        "spring.datasource.url=jdbc:h2:mem:authenticated-rest-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.h2.console.enabled=false",
    ],
)
@AutoConfigureMockMvc
class AuthenticatedRestIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val userAccess: TestGitHubUserAccessGateway,
) {
    @Test
    fun `기여자는 REST로 기록을 만들고 잘못된 중첩 입력은 거부된다`() {
        mockMvc.post("/api/v1/change-records") {
            authorized()
            contentType = MediaType.APPLICATION_JSON
            content = createRequest(UUID.randomUUID().toString(), "판단을 기록한다.")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.createdBy.subject") { value("github:42") }
        }

        mockMvc.post("/api/v1/change-records") {
            authorized()
            contentType = MediaType.APPLICATION_JSON
            content = createRequest(UUID.randomUUID().toString(), "")
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `비밀값 제거 후 제목 길이가 초과되면 원문 없이 입력 오류를 반환한다`() {
        mockMvc.post("/api/v1/change-records") {
            authorized()
            contentType = MediaType.APPLICATION_JSON
            content = createRequest(UUID.randomUUID().toString(), "판단을 기록한다.")
                .replace("REST 인증 계약", "password=x ".repeat(18))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("비밀값 제거 후 제목 길이는 200자 이하여야 합니다.") }
        }
    }

    @Test
    fun `인증 실패와 GitHub 사용자 조회 장애를 구분한다`() {
        mockMvc.get("/api/v1/change-records/${UUID.randomUUID()}")
            .andExpect { status { isUnauthorized() } }

        userAccess.failAuthentication = true
        try {
            mockMvc.get("/api/v1/change-records/${UUID.randomUUID()}") {
                authorized()
            }.andExpect { status { isBadGateway() } }
        } finally {
            userAccess.failAuthentication = false
        }
    }

    @Test
    fun `읽기 권한 사용자는 기록을 만들 수 없다`() {
        userAccess.role = RepositoryRole.READER
        try {
            mockMvc.post("/api/v1/change-records") {
                authorized()
                contentType = MediaType.APPLICATION_JSON
                content = createRequest(UUID.randomUUID().toString(), "판단을 기록한다.")
            }.andExpect { status { isForbidden() } }
        } finally {
            userAccess.role = RepositoryRole.MAINTAINER
        }
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.authorized() {
        header(HttpHeaders.AUTHORIZATION, "Bearer ghu_authenticated-rest-test")
    }

    private fun createRequest(requestId: String, decisionSummary: String): String =
        """
        {
          "requestId":"$requestId",
          "repositoryKey":"acme/intent-trace",
          "snapshotDigest":"${"a".repeat(64)}",
          "title":"REST 인증 계약",
          "requestSummary":"인증과 권한 상태를 확인한다.",
          "decisions":[{"summary":"$decisionSummary","source":"STATED_BY_USER"}],
          "codeAnchors":[{
            "relativePath":"src/App.kt",
            "startLine":1,
            "endLine":2,
            "contentHash":"${"b".repeat(64)}"
          }]
        }
        """.trimIndent()

    @TestConfiguration
    class RestTestConfiguration {
        @Bean
        @Primary
        fun gitHubUserAccessGateway(): TestGitHubUserAccessGateway = TestGitHubUserAccessGateway()
    }

    class TestGitHubUserAccessGateway : GitHubUserAccessGateway {
        var failAuthentication = false
        var role: RepositoryRole = RepositoryRole.MAINTAINER

        override fun authenticate(accessToken: String): ActorIdentity {
            if (failAuthentication) throw GitHubIdentityApiException("테스트 사용자 조회 장애")
            return ActorIdentity.github(42, "lim")
        }

        override fun repositoryRole(accessToken: String, repository: GitHubRepository): RepositoryRole = role
    }
}
