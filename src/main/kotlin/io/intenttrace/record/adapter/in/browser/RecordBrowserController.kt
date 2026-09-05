package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.config.GitHubProperties
import io.intenttrace.config.GitHubRateLimitException
import io.intenttrace.identity.adapter.`in`.web.BROWSER_SESSION_COOKIE
import io.intenttrace.identity.adapter.`in`.web.GitHubUserAuthenticationFilter
import io.intenttrace.identity.adapter.`in`.web.browserSessionCookie
import io.intenttrace.identity.application.BrowserReturnPath
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.GitHubOAuthException
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.identity.application.GitHubUserSessionStore
import io.intenttrace.identity.application.RepositoryAccessDeniedException
import io.intenttrace.record.application.ChangeRecordCatalogService
import io.intenttrace.record.application.ChangeRecordNotFoundException
import io.intenttrace.record.application.ChangeRecordOwnershipException
import io.intenttrace.record.application.RecordScope
import io.intenttrace.record.application.TeamChangeRecordService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.util.UriComponentsBuilder
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping("/records")
class RecordBrowserController(
    private val records: TeamChangeRecordService,
    private val catalog: ChangeRecordCatalogService,
    private val sessions: GitHubUserSessionStore,
    private val pages: RecordBrowserPage,
    private val properties: GitHubProperties,
) {
    @GetMapping
    fun search(
        request: HttpServletRequest,
        @RequestParam(required = false) repositoryKey: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "TEAM") scope: RecordScope,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<String> = read(request) { session ->
        val repository = repositoryKey?.trim()?.takeIf { it.isNotEmpty() }
        pages.search(session.actor, repository, q, scope,
            repository?.let { catalog.list(it, scope, cursor = cursor, q = q) })
    }

    @GetMapping("/{id}")
    fun record(request: HttpServletRequest, @PathVariable id: UUID): ResponseEntity<String> = read(request) {
        pages.record(it.actor, records.get(id))
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<String> {
        val callback = properties.userAuthorization.callbackUrl
        val defaultPort = (callback.scheme == "https" && callback.port == 443) || (callback.scheme == "http" && callback.port == 80)
        val origin = UriComponentsBuilder.fromUri(callback).replacePath(null).replaceQuery(null).fragment(null)
            .port(if (defaultPort) -1 else callback.port).build().toUriString()
        if (!origin.equals(request.getHeader(HttpHeaders.ORIGIN), ignoreCase = true)) return browserResponse(pages.error("같은 기록 화면에서 로그아웃해 주세요."), 403)
        request.cookies?.singleOrNull { it.name == BROWSER_SESSION_COOKIE }?.let { sessions.revokeBrowser(it.value) }
        return ResponseEntity.status(303).header(HttpHeaders.LOCATION, "/records")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.SET_COOKIE, browserSessionCookie(properties, "", Duration.ZERO).toString()).body("")
    }

    private fun read(request: HttpServletRequest, render: (GitHubUserSession) -> String): ResponseEntity<String> {
        val returnTo = BrowserReturnPath.validate(request.requestURI + request.queryString?.let { "?$it" }.orEmpty())
        val cookie = request.cookies?.singleOrNull { it.name == BROWSER_SESSION_COOKIE }
        val session = try {
            val token = cookie?.value?.takeIf { Regex("^itb_[A-Za-z0-9_-]{43}$").matches(it) }
                ?: throw GitHubUserAuthenticationException()
            sessions.resolve(token)
        } catch (_: GitHubUserAuthenticationException) {
            return browserResponse(pages.login(returnTo, cookie != null))
        }
        request.setAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE, session)
        return try { browserResponse(render(session)) }
        finally { request.removeAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE) }
    }

    @ExceptionHandler(ChangeRecordNotFoundException::class, ChangeRecordOwnershipException::class, RepositoryAccessDeniedException::class)
    fun unavailable(): ResponseEntity<String> = browserResponse(pages.error("기록이 없거나 열람 권한이 없습니다."), 404)

    @ExceptionHandler(IllegalArgumentException::class, MethodArgumentTypeMismatchException::class)
    fun invalid(): ResponseEntity<String> = browserResponse(pages.error("저장소, 검색어 또는 기록 주소를 확인해 주세요."), 400)

    @ExceptionHandler(GitHubIdentityApiException::class, GitHubOAuthException::class)
    fun dependencyFailure(): ResponseEntity<String> = browserResponse(pages.error("GitHub 연결을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."), 502)

    @ExceptionHandler(GitHubRateLimitException::class)
    fun rateLimited(exception: GitHubRateLimitException): ResponseEntity<String> {
        val response = browserResponse(pages.error("GitHub 호출 제한에 도달했습니다. ${exception.retryAfterSeconds}초 후 다시 시도해 주세요."), 429)
        return ResponseEntity.status(response.statusCode).headers(response.headers)
            .header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString()).body(response.body)
    }
}
