package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.config.GitHubApiException
import io.intenttrace.config.GitHubProperties
import io.intenttrace.config.GitHubRateLimitException
import io.intenttrace.connection.application.ConnectionDiagnostics
import io.intenttrace.identity.adapter.`in`.web.GitHubUserAuthenticationFilter.Companion.SESSION_ATTRIBUTE
import io.intenttrace.identity.adapter.`in`.web.browserSessionCookie
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.identity.application.GitHubUserSessionStore
import io.intenttrace.identity.application.MySessionService
import io.intenttrace.identity.application.RepositoryAccessDeniedException
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.publication.application.PullRequestOverviewService
import io.intenttrace.publication.application.TeamGitHubPublicationService
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.application.ChangeIntentHistoryService
import io.intenttrace.record.application.ChangeRecordCatalogService
import io.intenttrace.record.application.ChangeRecordMarkdownRenderer
import io.intenttrace.record.application.ChangeRecordNotFoundException
import io.intenttrace.record.application.ChangeRecordOwnershipException
import io.intenttrace.record.application.EvidenceUnavailableException
import io.intenttrace.record.application.GitHubContextNotFoundException
import io.intenttrace.record.application.GitHubContextPermissionException
import io.intenttrace.record.application.GitHubContextService
import io.intenttrace.record.application.RecordActivityService
import io.intenttrace.record.application.RecordComparisonService
import io.intenttrace.record.application.RecordEvidenceService
import io.intenttrace.record.application.RecordScope
import io.intenttrace.record.application.TeamChangeRecordService
import io.intenttrace.record.domain.ChangeRecordStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.propertyeditors.StringTrimmerEditor
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.WebDataBinder
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.InitBinder
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.Duration
import java.util.UUID

/** 인증과 보안 헤더는 [RecordBrowserConfiguration]의 인터셉터가 처리기보다 먼저 적용한다. */
@Controller
@RequestMapping("/records")
class RecordBrowserController(
    private val records: TeamChangeRecordService,
    private val catalog: ChangeRecordCatalogService,
    private val sessions: GitHubUserSessionStore,
    private val pages: RecordBrowserPage,
    private val markdown: ChangeRecordMarkdownRenderer,
    private val properties: GitHubProperties,
    private val comparison: RecordComparisonService,
    private val diagnostics: ConnectionDiagnostics,
    private val overview: PullRequestOverviewService,
    private val publisher: TeamGitHubPublicationService,
    private val githubContext: GitHubContextService,
    private val history: ChangeIntentHistoryService,
    private val evidence: RecordEvidenceService,
    private val activities: RecordActivityService,
    private val mySessions: MySessionService,
) {
    // 폼 입력의 앞뒤 공백을 지우고 빈 값은 입력하지 않은 것으로 받는다.
    @InitBinder
    fun trimParameters(binder: WebDataBinder) = binder.registerCustomEditor(String::class.java, StringTrimmerEditor(true))

    // 레이아웃 머리글에 쓸 로그인 이름만 모델에 넣는다. 세션과 토큰은 넣지 않는다. 로그아웃 POST에는 세션이 없다.
    @ModelAttribute("login")
    fun loginName(@RequestAttribute(SESSION_ATTRIBUTE, required = false) session: GitHubUserSession?): String? = session?.actor?.login

    @GetMapping
    fun search(
        request: HttpServletRequest,
        @RequestAttribute(SESSION_ATTRIBUTE) session: GitHubUserSession,
        @RequestParam repositoryKey: String?,
        @RequestParam q: String?,
        @RequestParam(defaultValue = "TEAM") scope: RecordScope,
        @RequestParam cursor: String?,
        @RequestParam status: ChangeRecordStatus?,
        @RequestParam path: String?,
        @RequestParam authorId: Long?,
    ): ModelAndView = pages.search(session.actor, repositoryKey, q, scope,
        repositoryKey?.let { catalog.list(it, scope, path = path, status = status, authorId = authorId, cursor = cursor, q = q) },
        status, path, authorId, returnTo(request))

    // 게시 목록 조회가 같은 권한 검사로 기록을 함께 읽는다.
    @GetMapping("/{id}")
    fun record(request: HttpServletRequest, @PathVariable id: UUID): ModelAndView = pages.record(publisher.targets(id), searchUrl(request))

    @GetMapping("/{id}/markdown")
    fun markdown(@PathVariable id: UUID): ResponseEntity<String> {
        val record = records.get(id)
        return ResponseEntity.ok().contentType(MediaType("text", "markdown", Charsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("intent-trace-${record.id}.md").build().toString())
            .body(markdown.render(record))
    }

    @GetMapping("/{id}/comparison")
    fun compare(request: HttpServletRequest, @PathVariable id: UUID, @RequestParam(defaultValue = "false") changesOnly: Boolean): ModelAndView =
        pages.comparison(comparison.compare(id), changesOnly, searchUrl(request))

    @GetMapping("/history")
    fun history(request: HttpServletRequest, @RequestParam repositoryKey: String?, @RequestParam revision: String?,
        @RequestParam path: String?, @RequestParam line: Int?, @RequestParam cursor: String?, @RequestParam retryRecordId: UUID?): ModelAndView {
        val supplied = listOf(repositoryKey, revision, path, line)
        require(supplied.all { it == null } || supplied.all { it != null }) { "저장소·커밋·파일·줄을 함께 입력해 주세요." }
        require(repositoryKey != null || (cursor == null && retryRecordId == null)) { "먼저 조회 조건을 입력해 주세요." }
        return pages.history(repositoryKey, revision, path, line, repositoryKey?.let {
            history.find(it, requireNotNull(revision), requireNotNull(path), requireNotNull(line), cursor, retryRecordId = retryRecordId)
        }, returnTo(request))
    }

    @GetMapping("/{id}/evidence")
    fun evidence(request: HttpServletRequest, @PathVariable id: UUID): ModelAndView = try {
        pages.evidence(evidence.check(id), searchUrl(request))
    } catch (failure: EvidenceUnavailableException) {
        pages.evidenceUnavailable(id, failure.reason, searchUrl(request))
    }

    @GetMapping("/{id}/activities")
    fun activities(request: HttpServletRequest, @PathVariable id: UUID, @RequestParam beforeVersion: Long?): ModelAndView =
        pages.activities(activities.list(id, beforeVersion), searchUrl(request))

    @GetMapping("/sessions")
    fun sessions(): ModelAndView = pages.sessions(mySessions.list())

    @PostMapping("/sessions/{id}/revoke")
    fun revokeSession(@RequestAttribute(SESSION_ATTRIBUTE) session: GitHubUserSession, @PathVariable id: UUID): ResponseEntity<Void> {
        mySessions.revoke(id)
        return if (id == session.sessionId) seeOther("/records", clearSession = true) else seeOther("/records/sessions", clearSession = false)
    }

    @PostMapping("/sessions/revoke-all")
    fun revokeAllSessions(): ResponseEntity<Void> {
        mySessions.revokeAll()
        return seeOther("/records", clearSession = true)
    }

    // 현재 브라우저 세션이 끝났으면 쿠키도 지운다.
    private fun seeOther(location: String, clearSession: Boolean): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(location))
            .apply { if (clearSession) header(HttpHeaders.SET_COOKIE, browserSessionCookie(properties, "", Duration.ZERO).toString()) }
            .build()

    @GetMapping("/pull-requests")
    fun pullRequests(request: HttpServletRequest, @RequestParam repositoryKey: String?, @RequestParam pullNumber: Int?,
        @RequestParam cursor: String?): ModelAndView {
        val repository = repositoryKey?.let(GitHubRepository::parse)
        require((repository == null) == (pullNumber == null)) { "저장소와 PR 번호를 함께 입력해 주세요." }
        return pages.pullRequests(repository?.key, pullNumber, repository?.let {
            overview.overview(GitHubPullRequestTarget(it.canonicalOwner, it.canonicalName, requireNotNull(pullNumber)), cursor)
        }, returnTo(request))
    }

    @GetMapping("/github")
    fun github(@RequestParam repositoryKey: String?, @RequestParam number: Int?, @RequestParam revision: String?,
        @RequestParam(defaultValue = "1") page: Int): ModelAndView {
        require(repositoryKey != null || (number == null && revision == null)) { "저장소를 함께 입력해 주세요." }
        return pages.github(repositoryKey,
            number?.let { githubContext.request(requireNotNull(repositoryKey), it) },
            revision?.let { githubContext.actions(requireNotNull(repositoryKey), it, page) }, page)
    }

    @GetMapping("/connection")
    fun connection(@RequestParam repositoryKey: String?, @RequestParam revision: String?, @RequestParam pullNumber: Int?): ModelAndView =
        pages.connection(repositoryKey, revision, pullNumber, repositoryKey?.let { diagnostics.diagnose(it, revision, pullNumber) })

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<Void> {
        browserCookie(request)?.let { sessions.revokeBrowser(it.value) }
        return seeOther("/records", clearSession = true)
    }

    private fun searchUrl(request: HttpServletRequest): String? {
        val query = request.queryString ?: return null
        val (path, searchParameters) = when (request.getParameter("from")) {
            "history" -> "/records/history" to setOf("repositoryKey", "revision", "path", "line", "cursor", "retryRecordId")
            "pull-requests" -> "/records/pull-requests" to setOf("repositoryKey", "pullNumber", "cursor")
            else -> "/records" to setOf("repositoryKey", "q", "scope", "status", "path", "authorId", "cursor")
        }
        val builder = UriComponentsBuilder.fromPath(path).query(query)
        builder.build().queryParams.keys.filterNot { it in searchParameters }.forEach { builder.replaceQueryParam(it) }
        return builder.build().takeIf { it.queryParams.isNotEmpty() }?.toUriString()
    }

    private fun retryUrl(request: HttpServletRequest): String? = request.takeIf { it.method == "GET" }?.let(::returnTo)

    // 오류 화면을 그리기 전에 GitHub 토큰이 든 세션을 요청에서 지운다.
    private fun failure(request: HttpServletRequest, status: HttpStatus, message: String, retryUrl: String? = null): ModelAndView {
        request.removeAttribute(SESSION_ATTRIBUTE)
        return pages.error(status, message, retryUrl)
    }

    @ExceptionHandler(BrowserOriginException::class)
    fun crossOrigin(exception: BrowserOriginException, request: HttpServletRequest): ModelAndView =
        failure(request, HttpStatus.FORBIDDEN, exception.message.orEmpty())

    @ExceptionHandler(ChangeRecordNotFoundException::class, ChangeRecordOwnershipException::class, RepositoryAccessDeniedException::class)
    fun unavailable(request: HttpServletRequest): ModelAndView = failure(request, HttpStatus.NOT_FOUND, "기록이 없거나 열람 권한이 없습니다.")

    @ExceptionHandler(GitHubContextNotFoundException::class, GitHubContextPermissionException::class)
    fun githubContextUnavailable(exception: RuntimeException, request: HttpServletRequest): ModelAndView = failure(request,
        if (exception is GitHubContextPermissionException) HttpStatus.FORBIDDEN else HttpStatus.NOT_FOUND, exception.message.orEmpty())

    @ExceptionHandler(IllegalArgumentException::class, MethodArgumentTypeMismatchException::class)
    fun invalid(request: HttpServletRequest): ModelAndView =
        failure(request, HttpStatus.BAD_REQUEST, "저장소, 검색어 또는 기록 주소를 확인해 주세요.")

    @ExceptionHandler(IllegalStateException::class)
    fun stateConflict(request: HttpServletRequest): ModelAndView =
        failure(request, HttpStatus.CONFLICT, "기록의 현재 상태에서는 확인할 수 없습니다. 먼저 작성자 확인을 완료해 주세요.")

    @ExceptionHandler(GitHubIdentityApiException::class, GitHubApiException::class)
    fun dependencyFailure(request: HttpServletRequest): ModelAndView =
        failure(request, HttpStatus.BAD_GATEWAY, "GitHub 연결을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.", retryUrl(request))

    @ExceptionHandler(GitHubRateLimitException::class)
    fun rateLimited(exception: GitHubRateLimitException, request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        response.setHeader(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString())
        return failure(request, HttpStatus.TOO_MANY_REQUESTS,
            "GitHub 호출 제한에 도달했습니다. ${exception.retryAfterSeconds}초 후 다시 시도해 주세요.", retryUrl(request))
    }

    // 쿠키가 있었는데 인증하지 못했으면 만료 안내를 함께 표시한다.
    @ExceptionHandler(GitHubUserAuthenticationException::class)
    fun login(request: HttpServletRequest): ModelAndView {
        request.removeAttribute(SESSION_ATTRIBUTE)
        return pages.login(returnTo(request), browserCookie(request) != null)
    }
}
