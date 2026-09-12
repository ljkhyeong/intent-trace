package io.intenttrace.record.adapter.`in`.web

import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.application.LocalGitHubUserSessionRequiredException
import io.intenttrace.identity.application.RepositoryAccessDeniedException
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.publication.application.ForkPullRequestUnsupportedException
import io.intenttrace.publication.application.GitHubCredentialConfigurationException
import io.intenttrace.publication.application.GitHubCredentialMissingException
import io.intenttrace.publication.application.GitHubPublicationContentTooLargeException
import io.intenttrace.publication.application.GitHubRepositoryMismatchException
import io.intenttrace.publication.application.PullRequestRevisionMismatchException
import io.intenttrace.record.application.ChangeRecordNotFoundException
import io.intenttrace.record.application.ChangeRecordOwnershipException
import io.intenttrace.record.application.ChangeRecordRequestConflictException
import io.intenttrace.record.application.ConcurrentChangeRecordUpdateException
import io.intenttrace.record.application.GitHubContextNotFoundException
import io.intenttrace.record.application.GitHubContextPermissionException
import io.intenttrace.record.application.EvidenceUnavailableException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpHeaders
import io.intenttrace.config.GitHubRateLimitException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(GitHubRateLimitException::class)
    fun rateLimited(exception: GitHubRateLimitException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString())
            .body(problem(HttpStatus.TOO_MANY_REQUESTS, "GitHub 호출 제한", exception.message).also {
                it.setProperty("code", "GITHUB_RATE_LIMITED")
                it.setProperty("retryAfterSeconds", exception.retryAfterSeconds)
            })

    @ExceptionHandler(ChangeRecordNotFoundException::class)
    fun notFound(exception: ChangeRecordNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "변경 기록 없음", exception.message)

    @ExceptionHandler(ConcurrentChangeRecordUpdateException::class)
    fun conflict(exception: ConcurrentChangeRecordUpdateException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "기록 버전 충돌", exception.message)

    @ExceptionHandler(ChangeRecordRequestConflictException::class)
    fun requestConflict(exception: ChangeRecordRequestConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "요청 ID 충돌", exception.message)

    @ExceptionHandler(GitHubUserAuthenticationException::class)
    fun githubUserAuthentication(exception: GitHubUserAuthenticationException): ProblemDetail =
        problem(HttpStatus.UNAUTHORIZED, "GitHub 사용자 인증 실패", exception.message)

    @ExceptionHandler(LocalGitHubUserSessionRequiredException::class)
    fun localGitHubUserSessionRequired(exception: LocalGitHubUserSessionRequiredException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "IntentTrace 세션 필요", exception.message)

    @ExceptionHandler(RepositoryAccessDeniedException::class, ChangeRecordOwnershipException::class)
    fun repositoryAccessDenied(exception: RuntimeException): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, "저장소 기록 접근 거부", exception.message)

    @ExceptionHandler(GitHubIdentityApiException::class)
    fun githubIdentityFailure(exception: GitHubIdentityApiException): ProblemDetail =
        problem(HttpStatus.BAD_GATEWAY, "GitHub 사용자 권한 조회 실패", exception.message)

    @ExceptionHandler(PullRequestRevisionMismatchException::class, GitHubRepositoryMismatchException::class, ForkPullRequestUnsupportedException::class)
    fun githubTargetConflict(exception: RuntimeException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "GitHub 게시 대상 불일치", exception.message)

    @ExceptionHandler(GitHubCredentialMissingException::class, GitHubCredentialConfigurationException::class)
    fun githubCredentialUnavailable(exception: RuntimeException): ProblemDetail =
        problem(HttpStatus.SERVICE_UNAVAILABLE, "GitHub 게시 인증 설정 오류", exception.message)

    @ExceptionHandler(GitHubPublicationContentTooLargeException::class)
    fun githubContentTooLarge(exception: GitHubPublicationContentTooLargeException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "GitHub 게시 글자 수 초과", exception.message)

    @ExceptionHandler(GitHubApiException::class)
    fun githubApiFailure(exception: GitHubApiException): ProblemDetail =
        problem(HttpStatus.BAD_GATEWAY, "GitHub API 요청 실패", exception.message)

    @ExceptionHandler(EvidenceUnavailableException::class)
    fun evidenceUnavailable(exception: EvidenceUnavailableException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "코드 확인 불가", exception.reason.message).also {
            it.setProperty("code", "EVIDENCE_UNAVAILABLE")
            it.setProperty("reason", exception.reason.name)
        }

    @ExceptionHandler(GitHubContextNotFoundException::class)
    fun githubContextNotFound(exception: GitHubContextNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "GitHub 자료 조회 불가", exception.message)

    @ExceptionHandler(GitHubContextPermissionException::class)
    fun githubContextPermission(exception: GitHubContextPermissionException): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, "GitHub 자료 조회 거부", exception.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidInput(exception: IllegalArgumentException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "입력값 오류", exception.message)

    @ExceptionHandler(IllegalStateException::class)
    fun invalidState(exception: IllegalStateException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "기록 처리 불가", exception.message)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(exception: MethodArgumentNotValidException): ProblemDetail {
        val detail = exception.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return problem(HttpStatus.BAD_REQUEST, "입력값 오류", detail)
    }

    private fun problem(status: HttpStatus, title: String, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: title).also { it.title = title }
}
