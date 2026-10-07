package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.config.GITHUB_OAUTH_CALLBACK_PATH
import io.intenttrace.config.GitHubProperties
import io.intenttrace.config.GitHubRateLimitException
import io.intenttrace.identity.application.GitHubOAuthApiException
import io.intenttrace.identity.application.GitHubOAuthCallbackException
import io.intenttrace.identity.application.GitHubOAuthException
import io.intenttrace.identity.application.GitHubOAuthCodeException
import io.intenttrace.identity.application.GitHubOAuthCapacityException
import io.intenttrace.identity.application.GitHubOAuthConfigurationException
import io.intenttrace.identity.application.GitHubOAuthDeniedException
import io.intenttrace.identity.application.GitHubOAuthFlowService
import io.intenttrace.identity.application.GitHubOAuthStateException
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.application.BROWSER_SESSION_TTL
import io.intenttrace.identity.application.OAUTH_STATE_TTL
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.Duration

/** 결과 화면(`templates/auth`)은 기록 화면 틀과 보안 헤더를 함께 쓴다. */
@Controller
@RequestMapping("/auth/github")
class GitHubOAuthController(
    private val flow: GitHubOAuthFlowService,
    private val properties: GitHubProperties,
) {
    @GetMapping("/start")
    fun start(@RequestParam returnTo: String?): ResponseEntity<Void> {
        val start = flow.start(returnTo)
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(start.authorizationUri)
            .header(HttpHeaders.SET_COOKIE, stateCookie(start.state, OAUTH_STATE_TTL))
            .build()
    }

    // 브라우저 로그인은 기록 화면으로 돌아가고, 도구 연결은 its_ 세션을 이 화면에서 한 번만 표시한다.
    @GetMapping("/callback", produces = [MediaType.TEXT_HTML_VALUE])
    fun callback(
        @RequestParam code: String?,
        @RequestParam state: String?,
        @RequestParam error: String?,
        @CookieValue(STATE_COOKIE) cookieState: String?,
        response: HttpServletResponse,
    ): ModelAndView {
        response.addHeader(HttpHeaders.SET_COOKIE, stateCookie("", Duration.ZERO))
        val completion = flow.complete(code, state, cookieState, error)
        val issued = completion.session
        completion.returnTo?.let {
            response.addHeader(HttpHeaders.SET_COOKIE, browserSessionCookie(properties, issued.sessionToken, BROWSER_SESSION_TTL).toString())
            return ModelAndView("redirect:${URI.create(it).toASCIIString()}", HttpStatus.SEE_OTHER)
        }
        return ModelAndView("auth/github-success", mapOf("title" to "GitHub 연결 완료", "issued" to issued))
    }

    @ExceptionHandler(
        GitHubOAuthException::class,
        GitHubUserAuthenticationException::class,
        GitHubIdentityApiException::class,
        GitHubRateLimitException::class,
        IllegalArgumentException::class,
    )
    fun failure(exception: RuntimeException, response: HttpServletResponse): ModelAndView {
        val callback = exception as? GitHubOAuthCallbackException
        val failure = callback?.cause ?: exception
        val (status, message) = when (failure) {
            is GitHubRateLimitException -> HttpStatus.TOO_MANY_REQUESTS to failure.message!!
            is GitHubOAuthCapacityException -> HttpStatus.TOO_MANY_REQUESTS to "GitHub 로그인 요청이 많습니다. 잠시 후 다시 시도해 주세요."
            is GitHubOAuthStateException, is GitHubOAuthCodeException ->
                HttpStatus.BAD_REQUEST to "로그인 요청을 확인할 수 없습니다. 아래 링크에서 다시 로그인해 주세요."
            is GitHubOAuthDeniedException, is GitHubUserAuthenticationException ->
                HttpStatus.UNAUTHORIZED to "GitHub 로그인을 완료하지 못했습니다."
            is GitHubOAuthConfigurationException ->
                HttpStatus.SERVICE_UNAVAILABLE to "서버의 GitHub 로그인 설정이 올바르지 않습니다. 운영자에게 문의해 주세요."
            is GitHubOAuthApiException, is GitHubIdentityApiException ->
                HttpStatus.BAD_GATEWAY to "GitHub 인증 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
            is IllegalArgumentException -> HttpStatus.BAD_REQUEST to "기록으로 돌아갈 주소가 올바르지 않습니다."
            else -> throw failure
        }
        if (failure is GitHubRateLimitException) response.setHeader(HttpHeaders.RETRY_AFTER, failure.retryAfterSeconds.toString())
        val returnTo = callback?.returnTo
        val retryUrl = if (returnTo == null) "/auth/github/start" else UriComponentsBuilder.fromPath("/auth/github/start")
            .queryParam("returnTo", "{returnTo}").encode().buildAndExpand(returnTo).toUriString()
        return ModelAndView("auth/github-error", mapOf("title" to "GitHub 연결 실패", "message" to message, "retryUrl" to retryUrl), status)
    }

    private fun stateCookie(value: String, maxAge: Duration): String = ResponseCookie.from(STATE_COOKIE, value)
        .httpOnly(true)
        .secure(properties.userAuthorization.secureCookie)
        .sameSite("Lax")
        .path(GITHUB_OAUTH_CALLBACK_PATH)
        .maxAge(maxAge)
        .build()
        .toString()

    companion object {
        const val STATE_COOKIE = "intent_trace_oauth_state"
    }
}

const val BROWSER_SESSION_COOKIE = "intent_trace_browser"

fun browserSessionCookie(properties: GitHubProperties, value: String, maxAge: Duration): ResponseCookie =
    ResponseCookie.from(BROWSER_SESSION_COOKIE, value).httpOnly(true)
        .secure(properties.userAuthorization.secureCookie).sameSite("Lax").path("/records").maxAge(maxAge).build()
