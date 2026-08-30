package io.intenttrace.identity.adapter.out.github

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse

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
    fun `저장소 응답의 push 권한을 기여자 역할로 해석한다`() {
        server.expect { request ->
            assertEquals("/user/repos", request.uri.path)
            val query = UriComponentsBuilder.fromUri(request.uri).build().queryParams
            assertEquals("owner,collaborator,organization_member", query.getFirst("affiliation"))
            assertEquals("100", query.getFirst("per_page"))
            assertEquals("1", query.getFirst("page"))
        }
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer user-token"))
            .andRespond(
                withSuccess(
                    """[{"full_name":"acme/intent-trace","permissions":{"pull":true,"push":true}}]""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(
            RepositoryRole.CONTRIBUTOR,
            client.repositoryRole("user-token", GitHubRepository("acme", "intent-trace")),
        )
        server.verify()
    }

    @Test
    fun `명시적 접근 목록에 없는 저장소는 역할 없음으로 반환한다`() {
        server.expect { request -> assertEquals("/user/repos", request.uri.path) }
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

        assertNull(client.repositoryRole("user-token", GitHubRepository("acme", "private")))
        server.verify()
    }

    @Test
    fun `다음 페이지에 있는 저장소 권한도 찾는다`() {
        server.expect { request ->
            val query = UriComponentsBuilder.fromUri(request.uri).build().queryParams
            assertEquals("1", query.getFirst("page"))
        }
            .andRespond(
                withSuccess("[]", MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.LINK, "<https://api.github.test/user/repos?page=2>; rel=\"next\""),
            )
        server.expect { request ->
            val query = UriComponentsBuilder.fromUri(request.uri).build().queryParams
            assertEquals("2", query.getFirst("page"))
        }
            .andRespond(
                withSuccess(
                    """[{"full_name":"acme/intent-trace","permissions":{"pull":true}}]""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(
            RepositoryRole.READER,
            client.repositoryRole("user-token", GitHubRepository("acme", "intent-trace")),
        )
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
        server.expect { request -> assertEquals("/user/repos", request.uri.path) }
            .andRespond(
                withSuccess(
                    """[{"full_name":"acme/intent-trace","permissions":{"pull":true,"maintain":true}}]""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(
            RepositoryRole.MAINTAINER,
            client.repositoryRole("user-token", GitHubRepository("acme", "intent-trace")),
        )
        server.verify()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/user", "/user/repos"])
    fun `사용자와 권한 응답 파싱 오류에 원문을 남기지 않는다`(path: String) {
        val marker = "test-private-response-marker"
        val body = if (path == "/user") {
            """{"id":"$marker","login":"lim"}"""
        } else {
            """[{"full_name":"acme/intent-trace","permissions":{"pull":"$marker"}}]"""
        }
        server.expect { request -> assertEquals(path, request.uri.path) }
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

        val exception = assertFailsWith<GitHubIdentityApiException> {
            if (path == "/user") {
                client.authenticate("user-token")
            } else {
                client.repositoryRole("user-token", GitHubRepository("acme", "intent-trace"))
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
