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
import io.intenttrace.identity.application.IssuedGitHubUserSession
import io.intenttrace.identity.application.BROWSER_SESSION_TTL
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.util.HtmlUtils
import org.springframework.web.util.UriComponentsBuilder
import java.time.Duration
import java.net.URI

@RestController
@RequestMapping("/auth/github")
class GitHubOAuthController(
    private val flow: GitHubOAuthFlowService,
    private val properties: GitHubProperties,
) {
    @GetMapping("/start")
    fun start(@RequestParam(required = false) returnTo: String?): ResponseEntity<Void> {
        val start = flow.start(returnTo)
        return secure(ResponseEntity.status(HttpStatus.FOUND))
            .location(start.authorizationUri)
            .header(HttpHeaders.SET_COOKIE, stateCookie(start.state, properties.userAuthorization.stateTtl))
            .build()
    }

    @GetMapping("/callback", produces = [MediaType.TEXT_HTML_VALUE])
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        @CookieValue(name = STATE_COOKIE, required = false) cookieState: String?,
        response: HttpServletResponse,
    ): ResponseEntity<String> {
        response.addHeader(HttpHeaders.SET_COOKIE, stateCookie("", Duration.ZERO))
        val completion = flow.complete(code, state, cookieState, error)
        val issued = completion.session
        completion.returnTo?.let {
            val cookie = browserSessionCookie(properties, issued.sessionToken, BROWSER_SESSION_TTL)
            return secure(ResponseEntity.status(HttpStatus.SEE_OTHER)).location(URI.create(it))
                .header(HttpHeaders.SET_COOKIE, cookie.toString()).body("")
        }
        return secure(ResponseEntity.ok())
            .contentType(HTML_UTF8)
            .body(successPage(issued))
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

@RestControllerAdvice(assignableTypes = [GitHubOAuthController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class GitHubOAuthExceptionHandler {
    @ExceptionHandler(
        GitHubOAuthException::class,
        GitHubUserAuthenticationException::class,
        GitHubIdentityApiException::class,
        GitHubRateLimitException::class,
        IllegalArgumentException::class,
    )
    fun failure(exception: RuntimeException): ResponseEntity<String> {
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
        val response = secure(ResponseEntity.status(status)).contentType(HTML_UTF8)
        if (failure is GitHubRateLimitException) response.header(HttpHeaders.RETRY_AFTER, failure.retryAfterSeconds.toString())
        return response.body(errorPage(message, callback?.returnTo))
    }
}

const val BROWSER_SESSION_COOKIE = "intent_trace_browser"

fun browserSessionCookie(properties: GitHubProperties, value: String, maxAge: Duration): ResponseCookie =
    ResponseCookie.from(BROWSER_SESSION_COOKIE, value).httpOnly(true)
        .secure(properties.userAuthorization.secureCookie).sameSite("Lax").path("/records").maxAge(maxAge).build()

private fun successPage(session: IssuedGitHubUserSession): String =
    page(
        title = "GitHub 연결 완료",
        content =
            """
            <p><strong>@${escapeHtml(session.actor.login)}</strong> 계정이 IntentTrace에 연결됐습니다.</p>
            <p>아래 세션 토큰은 이 화면에서만 확인할 수 있습니다. 도구의 세션 입력창에 입력하거나 <code>INTENT_TRACE_SESSION_TOKEN</code> 환경 변수로 전달하세요.</p>
            <pre><code>${session.sessionToken}</code></pre>
            <p>IntentTrace 서버를 재시작하면 다시 로그인해야 합니다.</p>
            """.trimIndent(),
    )

private fun errorPage(message: String, returnTo: String?): String {
    val retryUrl = if (returnTo == null) "/auth/github/start" else UriComponentsBuilder.fromPath("/auth/github/start")
        .queryParam("returnTo", "{returnTo}").encode().buildAndExpand(returnTo).toUriString()
    return page(
        title = "GitHub 연결 실패",
        content = "<p>${escapeHtml(message)}</p><p><a href=\"${escapeHtml(retryUrl)}\">다시 로그인</a></p>",
    )
}

private val HTML_UTF8 = MediaType("text", "html", Charsets.UTF_8)

private fun page(title: String, content: String): String =
    """
    <!doctype html>
    <html lang="ko">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>${escapeHtml(title)}</title>
      <style>
        body { max-width: 44rem; margin: 10vh auto; padding: 0 1.5rem; font: 16px/1.6 system-ui, sans-serif; color: #202124; }
        h1 { font-size: 1.8rem; }
        pre { padding: 1rem; overflow-wrap: anywhere; white-space: pre-wrap; background: #f4f5f7; border-radius: .5rem; }
        code { font-family: ui-monospace, monospace; }
        a { color: #0969da; }
      </style>
    </head>
    <body>
      <main>
        <h1>${escapeHtml(title)}</h1>
        $content
      </main>
    </body>
    </html>
    """.trimIndent()

private fun escapeHtml(value: String): String = HtmlUtils.htmlEscape(value, Charsets.UTF_8.name())

private fun <T : ResponseEntity.HeadersBuilder<T>> secure(builder: T): T = builder
    .cacheControl(CacheControl.noStore())
    .header("Pragma", "no-cache")
    .header("Referrer-Policy", "no-referrer")
    .header("X-Content-Type-Options", "nosniff")
    .header(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    )
