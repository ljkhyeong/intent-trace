package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.identity.application.GitHubUserCredentialProvider
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.identity.domain.ActorIdentity
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubUserAuthenticationFilterTest {
    private val credentials = FakeGitHubUserCredentialProvider()
    private val filter = GitHubUserAuthenticationFilter(credentials)

    @Test
    fun `보호 경로에 Bearer 토큰이 없으면 요청을 거부한다`() {
        val request = MockHttpServletRequest("GET", "/api/v1/change-records/1")
        val response = MockHttpServletResponse()
        var continued = false

        filter.doFilter(request, response, FilterChain { _, _ -> continued = true })

        assertEquals(401, response.status)
        assertFalse(continued)
        assertNull(credentials.authenticatedToken)
    }

    @Test
    fun `검증한 사용자 세션은 요청 처리 중에만 제공한다`() {
        val request = MockHttpServletRequest("POST", "/mcp")
        request.addHeader("Authorization", "Bearer ghu_user-token")
        val response = MockHttpServletResponse()
        var sessionVisible = false

        filter.doFilter(
            request,
            response,
            FilterChain { servletRequest, _ ->
                sessionVisible = servletRequest.getAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE) != null
            },
        )

        assertEquals("ghu_user-token", credentials.authenticatedToken)
        assertTrue(sessionVisible)
        assertNull(request.getAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE))
    }

    @Test
    fun `인증 중 호출 제한도 429와 재시도 대기 시간을 반환한다`() {
        val limited = GitHubUserAuthenticationFilter(object : GitHubUserCredentialProvider {
            override fun authenticate(bearerToken: String): GitHubUserSession = throw io.intenttrace.config.GitHubRateLimitException(120)
        })
        val request = MockHttpServletRequest("POST", "/mcp")
        request.addHeader("Authorization", "Bearer its_test")
        val response = MockHttpServletResponse()
        limited.doFilter(request, response, FilterChain { _, _ -> error("호출하면 안 되는 경로") })
        assertEquals(429, response.status)
        assertEquals("120", response.getHeader("Retry-After"))
    }

    private class FakeGitHubUserCredentialProvider : GitHubUserCredentialProvider {
        var authenticatedToken: String? = null

        override fun authenticate(bearerToken: String): GitHubUserSession {
            authenticatedToken = bearerToken
            return GitHubUserSession(ActorIdentity.github(42, "lim"), "ghu_resolved-token")
        }
    }
}
