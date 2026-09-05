package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.connection.application.ConnectionDiagnostics
import io.intenttrace.publication.application.PullRequestOverviewService
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.record.application.EvidenceUnavailableException
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.application.RecordComparisonService
import io.intenttrace.record.application.ChangeIntentHistoryService
import io.intenttrace.record.application.RecordEvidenceService
import io.intenttrace.record.application.RecordActivityService
import io.intenttrace.identity.application.MySessionService
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
    private val comparison: RecordComparisonService,
    private val diagnostics: ConnectionDiagnostics,
    private val overview: PullRequestOverviewService,
    private val history: ChangeIntentHistoryService,
    private val evidence: RecordEvidenceService,
    private val activities: RecordActivityService,
    private val mySessions: MySessionService,
) {
    @GetMapping
    fun search(
        request: HttpServletRequest,
        @RequestParam(required = false) repositoryKey: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "TEAM") scope: RecordScope,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) status: ChangeRecordStatus?,
        @RequestParam(required = false) path: String?,
        @RequestParam(required = false) authorId: Long?,
    ): ResponseEntity<String> = read(request) { session ->
        val repository = repositoryKey?.trim()?.takeIf { it.isNotEmpty() }
        pages.search(session.actor, repository, q, scope,
            repository?.let { catalog.list(it, scope, path = path?.takeIf(String::isNotEmpty), status = status, authorId = authorId, cursor = cursor, q = q) },
            status, path, authorId)
    }

    @GetMapping("/{id}")
    fun record(request: HttpServletRequest, @PathVariable id: UUID): ResponseEntity<String> = read(request) {
        pages.record(it.actor, records.get(id))
    }

    @GetMapping("/{id}/comparison")
    fun compare(request: HttpServletRequest, @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") changesOnly: Boolean): ResponseEntity<String> = read(request) {
        pages.comparison(it.actor, comparison.compare(id), changesOnly)
    }

    @GetMapping("/history")
    fun history(request: HttpServletRequest, @RequestParam(required = false) repositoryKey: String?,
        @RequestParam(required = false) revision: String?, @RequestParam(required = false) path: String?,
        @RequestParam(required = false) line: Int?, @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) retryRecordId: UUID?): ResponseEntity<String> = read(request) {
        val repository = repositoryKey?.trim()?.takeIf(String::isNotEmpty)
        val ref = revision?.trim()?.takeIf(String::isNotEmpty)
        val file = path?.takeIf(String::isNotEmpty)
        val supplied = listOf(repository, ref, file, line)
        require(supplied.all { value -> value == null } || supplied.all { value -> value != null }) { "저장소·커밋·파일·줄을 함께 입력해 주세요." }
        require(repository != null || (cursor == null && retryRecordId == null)) { "먼저 조회 조건을 입력해 주세요." }
        pages.history(it.actor, repository, ref, file, line, repository?.let { repo ->
            history.find(repo, requireNotNull(ref), requireNotNull(file), requireNotNull(line), cursor, retryRecordId = retryRecordId)
        })
    }

    @GetMapping("/{id}/evidence")
    fun evidence(request: HttpServletRequest, @PathVariable id: UUID): ResponseEntity<String> = authenticated(request, returnTo(request)) {
        try { browserResponse(pages.evidence(it.actor, evidence.check(id))) }
        catch (failure: EvidenceUnavailableException) {
            browserResponse(pages.evidenceUnavailable(it.actor, id, failure.reason), 422)
        }
    }

    @GetMapping("/{id}/activities")
    fun activities(request: HttpServletRequest, @PathVariable id: UUID,
        @RequestParam(required = false) beforeVersion: Long?): ResponseEntity<String> = read(request) {
        pages.activities(it.actor, activities.list(id, beforeVersion))
    }

    @GetMapping("/sessions")
    fun sessions(request: HttpServletRequest): ResponseEntity<String> = read(request) {
        pages.sessions(it.actor, mySessions.list())
    }

    @PostMapping("/sessions/{id}/revoke")
    fun revokeSession(request: HttpServletRequest, @PathVariable id: UUID): ResponseEntity<String> = manageSessions(request) {
        mySessions.revoke(id)
        id == it.sessionId
    }

    @PostMapping("/sessions/revoke-all")
    fun revokeAllSessions(request: HttpServletRequest): ResponseEntity<String> = manageSessions(request) {
        mySessions.revokeAll()
        true
    }

    private fun manageSessions(request: HttpServletRequest, action: (GitHubUserSession) -> Boolean): ResponseEntity<String> {
        if (!sameOrigin(request)) return browserResponse(pages.error("같은 기록 화면에서 연결을 종료해 주세요."), 403)
        return authenticated(request, "/records/sessions") { session ->
            val currentRevoked = action(session)
            val result = ResponseEntity.status(303).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.LOCATION, if (currentRevoked) "/records" else "/records/sessions")
            if (currentRevoked) result.header(HttpHeaders.SET_COOKIE, browserSessionCookie(properties, "", Duration.ZERO).toString())
            result.body("")
        }
    }

    @GetMapping("/pull-requests")
    fun pullRequests(request: HttpServletRequest, @RequestParam(required = false) repositoryKey: String?,
        @RequestParam(required = false) pullNumber: Int?, @RequestParam(required = false) cursor: String?): ResponseEntity<String> = read(request) {
        val repository = repositoryKey?.trim()?.takeIf(String::isNotEmpty)?.let(GitHubRepository::parse)
        require((repository == null) == (pullNumber == null)) { "저장소와 PR 번호를 함께 입력해 주세요." }
        pages.pullRequests(it.actor, repository?.key, pullNumber, repository?.let { repo ->
            overview.overview(GitHubPullRequestTarget(repo.canonicalOwner, repo.canonicalName, requireNotNull(pullNumber)), cursor)
        })
    }

    @GetMapping("/connection")
    fun connection(request: HttpServletRequest, @RequestParam(required = false) repositoryKey: String?,
        @RequestParam(required = false) revision: String?, @RequestParam(required = false) pullNumber: Int?): ResponseEntity<String> = read(request) {
        val repository = repositoryKey?.trim()?.takeIf(String::isNotEmpty)
        val ref = revision?.trim()?.takeIf(String::isNotEmpty)
        pages.connection(it.actor, repository, ref, pullNumber, repository?.let { repo -> diagnostics.diagnose(repo, ref, pullNumber) })
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<String> {
        if (!sameOrigin(request)) return browserResponse(pages.error("같은 기록 화면에서 로그아웃해 주세요."), 403)
        request.cookies?.singleOrNull { it.name == BROWSER_SESSION_COOKIE }?.let { sessions.revokeBrowser(it.value) }
        return ResponseEntity.status(303).header(HttpHeaders.LOCATION, "/records")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.SET_COOKIE, browserSessionCookie(properties, "", Duration.ZERO).toString()).body("")
    }

    private fun sameOrigin(request: HttpServletRequest): Boolean {
        val callback = properties.userAuthorization.callbackUrl
        val defaultPort = (callback.scheme == "https" && callback.port == 443) || (callback.scheme == "http" && callback.port == 80)
        val origin = UriComponentsBuilder.fromUri(callback).replacePath(null).replaceQuery(null).fragment(null)
            .port(if (defaultPort) -1 else callback.port).build().toUriString()
        return origin.equals(request.getHeader(HttpHeaders.ORIGIN), ignoreCase = true)
    }

    private fun returnTo(request: HttpServletRequest): String = if (request.method == "GET")
        BrowserReturnPath.validate(request.requestURI + request.queryString?.let { "?$it" }.orEmpty()) else "/records/sessions"

    private fun read(request: HttpServletRequest, render: (GitHubUserSession) -> String): ResponseEntity<String> =
        authenticated(request, returnTo(request)) { browserResponse(render(it)) }

    private fun authenticated(request: HttpServletRequest, returnTo: String,
        action: (GitHubUserSession) -> ResponseEntity<String>): ResponseEntity<String> {
        val cookie = request.cookies?.singleOrNull { it.name == BROWSER_SESSION_COOKIE }
        val session = try {
            val token = cookie?.value?.takeIf { Regex("^itb_[A-Za-z0-9_-]{43}$").matches(it) }
                ?: throw GitHubUserAuthenticationException()
            sessions.resolve(token)
        } catch (_: GitHubUserAuthenticationException) {
            return browserResponse(pages.login(returnTo, cookie != null))
        }
        request.setAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE, session)
        return try { action(session) }
        finally { request.removeAttribute(GitHubUserAuthenticationFilter.SESSION_ATTRIBUTE) }
    }

    @ExceptionHandler(ChangeRecordNotFoundException::class, ChangeRecordOwnershipException::class, RepositoryAccessDeniedException::class)
    fun unavailable(): ResponseEntity<String> = browserResponse(pages.error("기록이 없거나 열람 권한이 없습니다."), 404)

    @ExceptionHandler(IllegalArgumentException::class, MethodArgumentTypeMismatchException::class)
    fun invalid(): ResponseEntity<String> = browserResponse(pages.error("저장소, 검색어 또는 기록 주소를 확인해 주세요."), 400)

    @ExceptionHandler(IllegalStateException::class)
    fun stateConflict(): ResponseEntity<String> = browserResponse(pages.error("기록의 현재 상태에서는 확인할 수 없습니다. 먼저 작성자 확인을 완료해 주세요."), 409)

    @ExceptionHandler(GitHubIdentityApiException::class, GitHubOAuthException::class, GitHubApiException::class)
    fun dependencyFailure(): ResponseEntity<String> = browserResponse(pages.error("GitHub 연결을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."), 502)

    @ExceptionHandler(GitHubUserAuthenticationException::class)
    fun expired(request: HttpServletRequest): ResponseEntity<String> = browserResponse(pages.login(
        returnTo(request), true,
    ))

    @ExceptionHandler(GitHubRateLimitException::class)
    fun rateLimited(exception: GitHubRateLimitException): ResponseEntity<String> {
        val response = browserResponse(pages.error("GitHub 호출 제한에 도달했습니다. ${exception.retryAfterSeconds}초 후 다시 시도해 주세요."), 429)
        return ResponseEntity.status(response.statusCode).headers(response.headers)
            .header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString()).body(response.body)
    }
}
