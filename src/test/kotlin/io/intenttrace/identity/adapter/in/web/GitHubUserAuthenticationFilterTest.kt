package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.identity.application.GitHubUserOAuthTokens
import io.intenttrace.identity.application.GitHubUserSessionStore
import io.intenttrace.identity.application.IssuedGitHubUserSession
import io.intenttrace.identity.application.SessionChannel
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.domain.ActorIdentity
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tools.jackson.databind.ObjectMapper

class GitHubUserAuthenticationFilterTest {
    private val credentials = FakeSessionStore()
    private val mapper = ObjectMapper()
    private val filter = GitHubUserAuthenticationFilter(credentials, mapper)

    @Test
    fun `보호 경로에 Bearer 토큰이 없으면 요청을 거부한다`() {
        val request = MockHttpServletRequest("GET", "/api/v1/change-records/1")
        val response = MockHttpServletResponse()
        var continued = false

        filter.doFilter(request, response, FilterChain { _, _ -> continued = true })

        assertEquals(401, response.status)
        assertFalse(continued)
        assertNull(credentials.authenticatedToken)
        assertEquals("application/problem+json", response.contentType?.substringBefore(';'))
        assertEquals(mapper.readTree("""{"status":401,"title":"GitHub 사용자 인증 실패"}"""), mapper.readTree(response.contentAsString))
    }

    @Test
    fun `GitHub 토큰을 직접 보내면 세션 조회 없이 거부한다`() {
        val request = MockHttpServletRequest("POST", "/mcp")
        request.addHeader("Authorization", "Bearer ghu_user-token")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> error("호출하면 안 되는 경로") })

        assertEquals(401, response.status)
        assertNull(credentials.authenticatedToken)
    }

    @Test
    fun `검증한 사용자 세션은 요청 처리 중에만 제공한다`() {
        val request = MockHttpServletRequest("POST", "/mcp")
        request.addHeader("Authorization", "Bearer $sessionToken")
        val response = MockHttpServletResponse()
        var sessionVisible = false

        filter.doFilter(
            request,
            response,
            FilterChain { servletRequest, _ ->
                sessionVisible = servletRequest.getAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE) != null
            },
        )

        assertEquals(sessionToken, credentials.authenticatedToken)
        assertTrue(sessionVisible)
        assertNull(request.getAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE))
    }

    @Test
    fun `인증 중 호출 제한도 429와 재시도 대기 시간을 반환한다`() {
        val limited = GitHubUserAuthenticationFilter(FakeSessionStore { throw io.intenttrace.config.GitHubRateLimitException(120) }, mapper)
        val request = MockHttpServletRequest("POST", "/mcp")
        request.addHeader("Authorization", "Bearer its_test")
        val response = MockHttpServletResponse()
        limited.doFilter(request, response, FilterChain { _, _ -> error("호출하면 안 되는 경로") })
        assertEquals(429, response.status)
        assertEquals("120", response.getHeader("Retry-After"))
        assertEquals(mapper.readTree("""{"status":429,"title":"GitHub 호출 제한에 도달했습니다. 120초 후 다시 시도하세요."}"""), mapper.readTree(response.contentAsString))
    }

    @Test
    fun `인증 서버 실패는 원문 없는 502 JSON 응답으로 반환한다`() {
        val failing = GitHubUserAuthenticationFilter(FakeSessionStore { throw GitHubIdentityApiException("외부 응답 원문") }, mapper)
        val request = MockHttpServletRequest("POST", "/mcp")
        request.addHeader("Authorization", "Bearer its_test")
        val response = MockHttpServletResponse()

        failing.doFilter(request, response, FilterChain { _, _ -> error("호출하면 안 되는 경로") })

        assertEquals(502, response.status)
        assertEquals(mapper.readTree("""{"status":502,"title":"GitHub 사용자 인증 서비스 오류"}"""), mapper.readTree(response.contentAsString))
    }

    private val sessionToken = "its_${"A".repeat(43)}"

    private class FakeSessionStore(private val failure: (() -> Nothing)? = null) : GitHubUserSessionStore {
        var authenticatedToken: String? = null

        override fun resolve(sessionToken: String): GitHubUserSession {
            failure?.invoke()
            authenticatedToken = sessionToken
            return GitHubUserSession(ActorIdentity.github(42, "lim"), "ghu_resolved-token", java.util.UUID.randomUUID())
        }

        override fun issue(actor: ActorIdentity, tokens: GitHubUserOAuthTokens, channel: SessionChannel): IssuedGitHubUserSession =
            error("사용하지 않는 테스트 경로")

        override fun revokeBrowser(sessionToken: String) = error("사용하지 않는 테스트 경로")
    }
}
