package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.config.GitHubRateLimitException
import io.intenttrace.identity.application.GitHubUserOAuthTokens
import io.intenttrace.identity.application.GitHubUserSessionStore
import io.intenttrace.identity.application.IssuedGitHubUserSession
import io.intenttrace.identity.application.SessionChannel
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.domain.ActorIdentity
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GitHubUserAuthenticationFilterTest {
    private val credentials = FakeSessionStore()
    private var resolved: Exception? = null
    private val errors = HandlerExceptionResolver { _, response, _, exception ->
        resolved = exception
        response.status = 418
        ModelAndView()
    }
    private val filter = GitHubUserAuthenticationFilter(credentials, errors)

    @Test
    fun `Bearer 토큰이 없거나 GitHub 토큰을 직접 보내면 세션 조회 없이 인증 실패로 넘긴다`() {
        for (authorization in listOf(null, "Bearer ghu_user-token")) {
            resolved = null
            val request = MockHttpServletRequest("POST", "/mcp")
            authorization?.let { request.addHeader("Authorization", it) }
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, FilterChain { _, _ -> error("호출하면 안 되는 경로") })

            assertIs<GitHubUserAuthenticationException>(resolved)
            assertEquals(418, response.status)
            assertNull(credentials.authenticatedToken)
        }
    }

    @Test
    fun `세션 조회 중 호출 제한과 사용자 조회 장애는 같은 예외로 전역 처리에 넘긴다`() {
        for (failure in listOf(GitHubRateLimitException(120), GitHubIdentityApiException("테스트 사용자 조회 장애"))) {
            resolved = null
            val failing = GitHubUserAuthenticationFilter(FakeSessionStore { throw failure }, errors)
            val request = MockHttpServletRequest("POST", "/mcp")
            request.addHeader("Authorization", "Bearer $sessionToken")

            failing.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> error("호출하면 안 되는 경로") })

            assertSame(failure, resolved)
        }
    }

    @Test
    fun `전역 처리가 다루지 않는 예외는 다시 던진다`() {
        val unresolved = GitHubUserAuthenticationFilter(credentials) { _, _, _, _ -> null }
        val request = MockHttpServletRequest("GET", "/api/v1/change-records")

        assertFailsWith<GitHubUserAuthenticationException> {
            unresolved.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> error("호출하면 안 되는 경로") })
        }
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
        assertNull(resolved)
        assertNull(request.getAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE))
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
