package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.config.GITHUB_OAUTH_CALLBACK_PATH
import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.GitHubOAuthApiException
import io.intenttrace.identity.application.GitHubOAuthCodeException
import io.intenttrace.identity.application.GitHubOAuthConfigurationException
import io.intenttrace.identity.application.GitHubOAuthDeniedException
import io.intenttrace.identity.application.GitHubOAuthFlowService
import io.intenttrace.identity.application.GitHubOAuthStateException
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.application.IssuedGitHubUserSession
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
import java.time.Duration

@RestController
@RequestMapping("/auth/github")
class GitHubOAuthController(
    private val flow: GitHubOAuthFlowService,
    private val properties: GitHubProperties,
) {
    @GetMapping("/start")
    fun start(): ResponseEntity<Void> {
        val start = flow.start()
        val stateCookie = ResponseCookie.from(STATE_COOKIE, start.state)
            .httpOnly(true)
            .secure(properties.userAuthorization.secureCookie)
            .sameSite("Lax")
            .path(GITHUB_OAUTH_CALLBACK_PATH)
            .maxAge(properties.userAuthorization.stateTtl)
            .build()
        return secure(ResponseEntity.status(HttpStatus.FOUND))
            .location(start.authorizationUri)
            .header(HttpHeaders.SET_COOKIE, stateCookie.toString())
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
        response.addHeader(HttpHeaders.SET_COOKIE, expiredStateCookie())
        val issued = flow.complete(code, state, cookieState, error)
        return secure(ResponseEntity.ok())
            .contentType(HTML_UTF8)
            .body(successPage(issued))
    }

    private fun expiredStateCookie(): String = ResponseCookie.from(STATE_COOKIE, "")
        .httpOnly(true)
        .secure(properties.userAuthorization.secureCookie)
        .sameSite("Lax")
        .path(GITHUB_OAUTH_CALLBACK_PATH)
        .maxAge(Duration.ZERO)
        .build()
        .toString()

    companion object {
        const val STATE_COOKIE = "intent_trace_oauth_state"
    }
}

@RestControllerAdvice(assignableTypes = [GitHubOAuthController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class GitHubOAuthExceptionHandler {
    @ExceptionHandler(GitHubOAuthStateException::class, GitHubOAuthCodeException::class)
    fun invalidRequest(): ResponseEntity<String> =
        secure(ResponseEntity.status(HttpStatus.BAD_REQUEST))
            .contentType(HTML_UTF8)
            .body(errorPage("GitHub 승인을 확인할 수 없습니다."))

    @ExceptionHandler(GitHubOAuthDeniedException::class, GitHubUserAuthenticationException::class)
    fun denied(): ResponseEntity<String> =
        secure(ResponseEntity.status(HttpStatus.UNAUTHORIZED))
            .contentType(HTML_UTF8)
            .body(errorPage("GitHub 승인이 완료되지 않았습니다."))

    @ExceptionHandler(GitHubOAuthConfigurationException::class)
    fun unavailable(): ResponseEntity<String> =
        secure(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE))
            .contentType(HTML_UTF8)
            .body(errorPage("GitHub 승인 설정을 확인해 주세요."))

    @ExceptionHandler(GitHubOAuthApiException::class, GitHubIdentityApiException::class)
    fun dependencyFailure(): ResponseEntity<String> =
        secure(ResponseEntity.status(HttpStatus.BAD_GATEWAY))
            .contentType(HTML_UTF8)
            .body(errorPage("GitHub 승인 요청을 완료하지 못했습니다."))
}

private fun successPage(session: IssuedGitHubUserSession): String =
    page(
        title = "GitHub 연결 완료",
        content =
            """
            <p><strong>@${escapeHtml(session.actor.login)}</strong> 계정이 IntentTrace에 연결됐습니다.</p>
            <p>아래 session token은 이 화면에서만 확인할 수 있습니다. <code>INTENT_TRACE_SESSION_TOKEN</code> 환경 변수에 저장하세요.</p>
            <pre><code>${session.sessionToken}</code></pre>
            <p>IntentTrace를 재시작하면 이 session은 사라지며 다시 연결해야 합니다.</p>
            """.trimIndent(),
    )

private fun errorPage(message: String): String = page(
    title = "GitHub 연결 실패",
    content = "<p>${escapeHtml(message)}</p><p><a href=\"/auth/github/start\">다시 연결하기</a></p>",
)

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
