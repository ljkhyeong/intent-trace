package io.intenttrace.publication.application

import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import java.time.Instant
import java.util.UUID

enum class PublicationOperation { PUBLISH, SUPERSESSION_NOTICE }
enum class PublicationAttemptStatus { IN_PROGRESS, SUCCEEDED, FAILED, RESULT_UNKNOWN }

data class PublicationAttempt(
    val id: UUID,
    val operation: PublicationOperation,
    val status: PublicationAttemptStatus,
    val failureCode: String?,
    val checkRunId: Long?,
    val contentDigest: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

data class GitHubPublicationStatus(val publication: GitHubPublication?, val attempts: List<PublicationAttempt>)

interface GitHubPublicationTracking {
    fun start(recordId: UUID, target: GitHubPullRequestTarget, operation: PublicationOperation): UUID
    fun finish(attemptId: UUID, status: PublicationAttemptStatus, failureCode: String?, publication: GitHubPublication?)
    fun recent(recordId: UUID, target: GitHubPullRequestTarget): List<PublicationAttempt>
}
