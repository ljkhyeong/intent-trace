package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.identity.application.GitHubUserCredentialProvider
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
    private val credentials = FakeGitHubUserCredentialProvider()
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
        }, mapper)
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
        val failing = GitHubUserAuthenticationFilter(object : GitHubUserCredentialProvider {
            override fun authenticate(bearerToken: String): GitHubUserSession = throw GitHubIdentityApiException("외부 응답 원문")
        }, mapper)
        val request = MockHttpServletRequest("POST", "/mcp")
        request.addHeader("Authorization", "Bearer its_test")
        val response = MockHttpServletResponse()

        failing.doFilter(request, response, FilterChain { _, _ -> error("호출하면 안 되는 경로") })

        assertEquals(502, response.status)
        assertEquals(mapper.readTree("""{"status":502,"title":"GitHub 사용자 인증 서비스 오류"}"""), mapper.readTree(response.contentAsString))
    }

    private class FakeGitHubUserCredentialProvider : GitHubUserCredentialProvider {
        var authenticatedToken: String? = null

        override fun authenticate(bearerToken: String): GitHubUserSession {
            authenticatedToken = bearerToken
            return GitHubUserSession(ActorIdentity.github(42, "lim"), "ghu_resolved-token")
        }
    }
}
