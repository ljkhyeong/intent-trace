package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.adapter.`in`.web.BROWSER_SESSION_COOKIE
import io.intenttrace.identity.adapter.`in`.web.GitHubUserAuthenticationFilter
import io.intenttrace.identity.application.BrowserReturnPath
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.application.GitHubUserSessionStore
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.UriComponentsBuilder

@Configuration
class RecordBrowserConfiguration(
    private val sessions: GitHubUserSessionStore,
    private val properties: GitHubProperties,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        // GitHub 로그인 화면도 기록 화면 틀을 쓴다. 헤더는 인증보다 먼저 설정해 로그인·오류 화면에도 붙인다.
        registry.addInterceptor(BrowserSecurityHeaders).addPathPatterns("/records/**", "/auth/github/**")
        registry.addInterceptor(RecordBrowserInterceptor(sessions, properties)).addPathPatterns("/records/**")
    }
}

/** 기록 화면의 POST가 설정한 공개 origin에서 오지 않았다. */
class BrowserOriginException(message: String) : RuntimeException(message)

private object BrowserSecurityHeaders : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().headerValue)
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Content-Security-Policy",
            "default-src 'none'; style-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
        return true
    }
}

/**
 * 브라우저 세션을 확인하고 처리기 실행 동안만 요청 속성에 둔다.
 * POST는 세션을 확인하기 전에 출처부터 검사해 다른 출처 요청이 GitHub 호출이나 토큰 갱신을 일으키지 않게 한다.
 */
private class RecordBrowserInterceptor(
    private val sessions: GitHubUserSessionStore,
    private val properties: GitHubProperties,
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        // 기록 화면 처리기만 확인한다. 정적 자원·CORS preflight 처리기에는 화면 예외 처리기가 적용되지 않는다.
        if (handler !is HandlerMethod || handler.beanType != RecordBrowserController::class.java) return true
        val logout = request.method == "POST" && request.requestURI == "${request.contextPath}/records/logout"
        if (request.method == "POST" && !sameOrigin(request)) {
            throw BrowserOriginException(if (logout) "같은 기록 화면에서 로그아웃해 주세요." else "같은 기록 화면에서 연결을 종료해 주세요.")
        }
        // 로그아웃은 세션이 만료되거나 GitHub 장애 중이어도 로컬에서 처리한다.
        if (logout) return true
        // 잘못된 복귀 주소는 로그인 안내 전에 400으로 거부한다.
        returnTo(request)
        val token = browserCookie(request)?.value?.takeIf { BROWSER_TOKEN.matches(it) } ?: throw GitHubUserAuthenticationException()
        request.setAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE, sessions.resolve(token))
        return true
    }

    // GitHub 토큰이 든 세션은 화면을 그리기 전에 지운다. 오류 화면은 컨트롤러 예외 처리기가 지운다.
    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) =
        request.removeAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE)

    override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?) =
        request.removeAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE)

    private fun sameOrigin(request: HttpServletRequest): Boolean {
        val callback = properties.userAuthorization.callbackUrl
        val defaultPort = (callback.scheme == "https" && callback.port == 443) || (callback.scheme == "http" && callback.port == 80)
        val origin = UriComponentsBuilder.fromUri(callback).replacePath(null).replaceQuery(null).fragment(null)
            .port(if (defaultPort) -1 else callback.port).build().toUriString()
        return origin.equals(request.getHeader(HttpHeaders.ORIGIN), ignoreCase = true)
    }
}

private val BROWSER_TOKEN = Regex("^itb_[A-Za-z0-9_-]{43}$")

internal fun browserCookie(request: HttpServletRequest): Cookie? = request.cookies?.singleOrNull { it.name == BROWSER_SESSION_COOKIE }

/** 로그인 후 돌아올 주소다. POST는 요청을 다시 실행하지 않도록 연결 목록으로 돌아온다. */
internal fun returnTo(request: HttpServletRequest): String = if (request.method == "GET")
    BrowserReturnPath.validate(request.requestURI + request.queryString?.let { "?$it" }.orEmpty()) else "/records/sessions"
