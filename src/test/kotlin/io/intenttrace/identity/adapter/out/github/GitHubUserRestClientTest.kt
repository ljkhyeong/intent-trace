package io.intenttrace.identity.adapter.out.github

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GitHubUserRestClientTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = GitHubUserRestClient(
        restClientBuilder = builder,
        properties = GitHubProperties(
            apiBaseUrl = URI.create("https://api.github.test"),
            apiVersion = "2026-03-10",
        ),
    )
    private val actor = ActorIdentity.github(42, "lim")

    @Test
    fun `사용자 토큰으로 안정적인 GitHub 작성자 식별자를 만든다`() {
        server.expect(requestTo("https://api.github.test/user"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer user-token"))
            .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
            .andRespond(withSuccess("""{"id":42,"login":"lim"}""", MediaType.APPLICATION_JSON))

        assertEquals(ActorIdentity.github(42, "lim"), client.authenticate("user-token"))
        server.verify()
    }

    @Test
    fun `GitHub 사용자 응답 값이 올바르지 않으면 연동 오류로 변환한다`() {
        server.expect(requestTo("https://api.github.test/user"))
            .andRespond(withSuccess("""{"id":0,"login":"lim"}""", MediaType.APPLICATION_JSON))

        val exception = assertFailsWith<GitHubIdentityApiException> {
            client.authenticate("user-token")
        }

        assertEquals("GitHub 사용자 응답 값이 올바르지 않습니다.", exception.message)
        server.verify()
    }

    @Test
    fun `저장소의 write 권한을 단건 조회해 기여자 역할로 해석한다`() {
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/collaborators/lim/permission"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer user-token"))
            .andRespond(
                withSuccess(
                    """{"permission":"write","role_name":"write","user":{"id":42,"login":"lim"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(
            RepositoryRole.CONTRIBUTOR,
            client.repositoryRole("user-token", actor, GitHubRepository("acme", "intent-trace")),
        )
        server.verify()
    }

    @Test
    fun `접근할 수 없는 저장소의 404 응답은 역할 없음으로 반환한다`() {
        server.expect(requestTo("https://api.github.test/repos/acme/private/collaborators/lim/permission"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertNull(client.repositoryRole("user-token", actor, GitHubRepository("acme", "private")))
        server.verify()
    }

    @Test
    fun `저장소의 read 권한을 조회 역할로 해석한다`() {
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/collaborators/lim/permission"))
            .andRespond(
                withSuccess(
                    """{"permission":"read","role_name":"read","user":{"id":42,"login":"lim"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(
            RepositoryRole.READER,
            client.repositoryRole("user-token", actor, GitHubRepository("acme", "intent-trace")),
        )
        server.verify()
    }

    @Test
    fun `저장소의 none 권한은 역할 없음으로 해석한다`() {
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/collaborators/lim/permission"))
            .andRespond(
                withSuccess(
                    """{"permission":"none","role_name":null,"user":{"id":42,"login":"lim"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertNull(client.repositoryRole("user-token", actor, GitHubRepository("acme", "intent-trace")))
        server.verify()
    }

    @Test
    fun `거부된 사용자 토큰은 인증 실패로 변환한다`() {
        server.expect(requestTo("https://api.github.test/user"))
            .andRespond(withUnauthorizedRequest())

        assertFailsWith<GitHubUserAuthenticationException> {
            client.authenticate("expired-token")
        }
        server.verify()
    }

    @Test
    fun `maintain 권한은 관리자 역할로 해석한다`() {
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/collaborators/lim/permission"))
            .andRespond(
                withSuccess(
                    """{"permission":"write","role_name":"maintain","user":{"id":42,"login":"lim"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(
            RepositoryRole.MAINTAINER,
            client.repositoryRole("user-token", actor, GitHubRepository("acme", "intent-trace")),
        )
        server.verify()
    }

    @Test
    fun `권한 응답 사용자가 현재 세션 사용자와 다르면 연동 오류로 처리한다`() {
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/collaborators/lim/permission"))
            .andRespond(
                withSuccess(
                    """{"permission":"write","role_name":"write","user":{"id":84,"login":"teammate"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val exception = assertFailsWith<GitHubIdentityApiException> {
            client.repositoryRole("user-token", actor, GitHubRepository("acme", "intent-trace"))
        }

        assertEquals("GitHub 저장소 권한 응답 사용자가 현재 사용자와 일치하지 않습니다.", exception.message)
        server.verify()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/user", "/repos/acme/intent-trace/collaborators/lim/permission"])
    fun `사용자와 권한 응답 파싱 오류에 원문을 남기지 않는다`(path: String) {
        val marker = "test-private-response-marker"
        val body = if (path == "/user") {
            """{"id":"$marker","login":"lim"}"""
        } else {
            """{"permission":{"value":"$marker"},"role_name":"write","user":{"id":42,"login":"lim"}}"""
        }
        server.expect { request -> assertEquals(path, request.uri.path) }
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

        val exception = assertFailsWith<GitHubIdentityApiException> {
            if (path == "/user") {
                client.authenticate("user-token")
            } else {
                client.repositoryRole("user-token", actor, GitHubRepository("acme", "intent-trace"))
            }
        }

        val operation = if (path == "/user") "사용자 조회" else "저장소 권한 조회"
        assertEquals("GitHub $operation 요청을 완료하지 못했습니다.", exception.message)
        assertFalse(exception.stackTraceToString().contains(marker))
        server.verify()
    }

    @Test
    fun `GitHub 장애 응답 본문은 예외에 노출하지 않는다`() {
        server.expect(requestTo("https://api.github.test/user"))
            .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).body("token-secret"))

        val exception = assertFailsWith<GitHubIdentityApiException> {
            client.authenticate("user-token")
        }

        assertFalse(exception.message.orEmpty().contains("token-secret"))
        server.verify()
    }
}
