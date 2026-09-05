package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserCredentialProvider
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.GitHubOAuthException
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.config.GitHubRateLimitException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class RequestGitHubUserSession(
    private val request: HttpServletRequest,
) : CurrentGitHubUserSession {
    override fun require(): GitHubUserSession =
        request.getAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE) as? GitHubUserSession
        ?: throw GitHubUserAuthenticationException()
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class GitHubUserAuthenticationFilter(
    private val credentials: GitHubUserCredentialProvider,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return !path.startsWith("/api/v1/") && path != "/api/v1" && !path.startsWith("/mcp")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val accessToken = bearerToken(request)
        if (accessToken == null) {
            unauthorized(response)
            return
        }

        try {
            request.setAttribute(SESSION_ATTRIBUTE, credentials.authenticate(accessToken))
            filterChain.doFilter(request, response)
        } catch (exception: GitHubRateLimitException) {
            response.setHeader(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString())
            problem(response, 429, exception.message ?: "GitHub 호출 제한")
        } catch (_: GitHubUserAuthenticationException) {
            unauthorized(response)
        } catch (_: GitHubIdentityApiException) {
            dependencyFailure(response)
        } catch (_: GitHubOAuthException) {
            dependencyFailure(response)
        } finally {
            request.removeAttribute(SESSION_ATTRIBUTE)
        }
    }

    private fun bearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        val token = header.substring(BEARER_PREFIX.length).trim()
        return token.takeIf {
            (it.startsWith(GITHUB_USER_TOKEN_PREFIX) || it.startsWith(INTENT_TRACE_SESSION_TOKEN_PREFIX)) &&
                it.length <= MAX_TOKEN_LENGTH &&
                it.none(Char::isWhitespace)
        }
    }

    private fun unauthorized(response: HttpServletResponse) {
        problem(response, HttpServletResponse.SC_UNAUTHORIZED, "GitHub 사용자 인증 실패")
    }

    private fun dependencyFailure(response: HttpServletResponse) {
        problem(response, HttpServletResponse.SC_BAD_GATEWAY, "GitHub 사용자 인증 서비스 오류")
    }

    private fun problem(response: HttpServletResponse, status: Int, title: String) {
        response.status = status
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write("""{"status":$status,"title":"$title"}""")
    }

    companion object {
        const val SESSION_ATTRIBUTE = "io.intenttrace.github-user-session"
        private const val BEARER_PREFIX = "Bearer "
        private const val GITHUB_USER_TOKEN_PREFIX = "ghu_"
        private const val INTENT_TRACE_SESSION_TOKEN_PREFIX = "its_"
        private const val MAX_TOKEN_LENGTH = 8_192
    }
}
