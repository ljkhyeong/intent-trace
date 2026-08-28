package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.identity.application.GitHubUserAccessGateway
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubUserAuthenticationFilterTest {
    private val gateway = FakeGitHubUserAccessGateway()
    private val filter = GitHubUserAuthenticationFilter(gateway)

    @Test
    fun `보호 경로에 Bearer 토큰이 없으면 요청을 거부한다`() {
        val request = MockHttpServletRequest("GET", "/api/v1/change-records/1")
        val response = MockHttpServletResponse()
        var continued = false

        filter.doFilter(request, response, FilterChain { _, _ -> continued = true })

        assertEquals(401, response.status)
        assertFalse(continued)
        assertNull(gateway.authenticatedToken)
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

        assertEquals("ghu_user-token", gateway.authenticatedToken)
        assertTrue(sessionVisible)
        assertNull(request.getAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE))
    }

    private class FakeGitHubUserAccessGateway : GitHubUserAccessGateway {
        var authenticatedToken: String? = null

        override fun authenticate(accessToken: String): ActorIdentity {
            authenticatedToken = accessToken
            return ActorIdentity.github(42, "lim")
        }

        override fun repositoryRole(accessToken: String, repository: GitHubRepository): RepositoryRole? =
            RepositoryRole.MAINTAINER
    }
}
