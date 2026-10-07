package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserSessionStore
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.application.GitHubUserSession
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.web.servlet.FilterRegistration
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver

@Component
class RequestGitHubUserSession(
    private val request: HttpServletRequest,
) : CurrentGitHubUserSession {
    override fun require(): GitHubUserSession =
        request.getAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE) as? GitHubUserSession
        ?: throw GitHubUserAuthenticationException()
}

// 적용 경로는 서블릿 컨테이너가 정규화한 경로로 판정해 `;` 경로 매개변수·인코딩으로 우회하지 못하게 한다.
@Component
@FilterRegistration(urlPatterns = ["/api/v1/*", "/mcp/*"], order = Ordered.HIGHEST_PRECEDENCE + 20)
class GitHubUserAuthenticationFilter(
    private val sessions: GitHubUserSessionStore,
    @Qualifier("handlerExceptionResolver") private val errors: HandlerExceptionResolver,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // 인증 실패·사용자 조회 장애·호출 제한은 컨트롤러와 같은 전역 예외 처리로 응답한다.
        val session = try {
            sessions.resolve(bearerToken(request) ?: throw GitHubUserAuthenticationException())
        } catch (exception: RuntimeException) {
            errors.resolveException(request, response, null, exception) ?: throw exception
            return
        }
        request.setAttribute(SESSION_ATTRIBUTE, session)
        try {
            filterChain.doFilter(request, response)
        } finally {
            request.removeAttribute(SESSION_ATTRIBUTE)
        }
    }

    private fun bearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        val token = header.substring(BEARER_PREFIX.length).trim()
        // REST·MCP는 IntentTrace가 발급한 its_ 세션만 받는다. GitHub 토큰은 서버 메모리에만 둔다.
        return token.takeIf { it.startsWith(SESSION_TOKEN_PREFIX) && it.length <= MAX_TOKEN_LENGTH && it.none(Char::isWhitespace) }
    }

    companion object {
        const val SESSION_ATTRIBUTE = "io.intenttrace.github-user-session"
        private const val BEARER_PREFIX = "Bearer "
        private const val SESSION_TOKEN_PREFIX = "its_"
        private const val MAX_TOKEN_LENGTH = 8_192
    }
}
