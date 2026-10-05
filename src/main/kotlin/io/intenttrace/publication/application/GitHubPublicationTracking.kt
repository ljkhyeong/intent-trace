package io.intenttrace.publication.application

import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.domain.ChangeRecord
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

/** PR별 최신 시도와 대체 안내 반영에 성공한 적이 있는지 여부다. */
data class PublicationTargetAttempt(val target: GitHubPullRequestTarget, val latest: PublicationAttempt, val supersessionNoticed: Boolean)

/** 기록 하나를 게시했거나 게시를 시도한 PR이다. */
data class RecordPublicationTarget(
    val repositoryKey: String,
    val pullNumber: Int,
    val publication: GitHubPublication?,
    val latestAttempt: PublicationAttempt?,
    val supersessionNoticeNeeded: Boolean,
)

data class RecordPublications(val record: ChangeRecord, val items: List<RecordPublicationTarget>, val truncated: Boolean)

interface GitHubPublicationTracking {
    fun start(recordId: UUID, target: GitHubPullRequestTarget, operation: PublicationOperation): UUID
    fun finish(attemptId: UUID, status: PublicationAttemptStatus, failureCode: String?, publication: GitHubPublication?)
    fun recent(recordId: UUID, target: GitHubPullRequestTarget): List<PublicationAttempt>
    fun latest(recordIds: Collection<UUID>, target: GitHubPullRequestTarget): Map<UUID, PublicationAttempt>
    fun latestByTarget(recordId: UUID, limit: Int): List<PublicationTargetAttempt>
}
