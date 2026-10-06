package io.intenttrace.publication.application

import io.intenttrace.config.GitHubRateLimitException
import io.micrometer.core.instrument.MeterRegistry
import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.record.application.TeamChangeRecordService
import io.intenttrace.record.domain.ChangeRecordStatus
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
class TeamGitHubPublicationService(
    private val records: TeamChangeRecordService,
    private val publisher: PublishChangeRecordToGitHub,
    private val tracking: GitHubPublicationTracking,
    private val publications: GitHubPublicationRepository,
    private val meters: MeterRegistry,
) {
    private val locks = Array(64) { ReentrantLock() }

    fun publish(command: PublishChangeRecordToGitHubCommand): GitHubPublication {
        return execute(command, PublicationOperation.PUBLISH)
    }

    fun syncSupersession(command: PublishChangeRecordToGitHubCommand): GitHubPublication {
        return execute(command, PublicationOperation.SUPERSESSION_NOTICE)
    }

    fun status(command: PublishChangeRecordToGitHubCommand): GitHubPublicationStatus {
        val record = records.get(command.changeRecordId)
        require(record.repositoryKey == command.target.repositoryKey) { "기록 저장소와 게시 조회 대상이 다릅니다." }
        return GitHubPublicationStatus(publications.find(record.id, command.target), tracking.recent(record.id, command.target))
    }

    /** 기록을 게시했거나 게시를 시도한 PR을 최근 순으로 모은다. 대체된 기록은 대체 안내가 필요한 PR을 표시한다. */
    fun targets(recordId: UUID): RecordPublications {
        val record = records.get(recordId)
        val published = publications.findByRecord(record.id, MAX_TARGETS + 1).associateBy { it.target.repositoryKey to it.target.pullNumber }
        val attempted = tracking.latestByTarget(record.id, MAX_TARGETS + 1).associateBy { it.repositoryKey to it.pullNumber }
        val items = (published.keys + attempted.keys).map { key ->
            val publication = published[key]
            val attempt = attempted[key]
            RecordPublicationTarget(key.first, key.second, publication, attempt?.latest,
                record.status == ChangeRecordStatus.SUPERSEDED && publication != null && attempt?.supersessionNoticed != true)
        }.sortedByDescending { maxOf(it.publication?.publishedAt ?: Instant.MIN, it.latestAttempt?.startedAt ?: Instant.MIN) }
        return RecordPublications(record, items.take(MAX_TARGETS), items.size > MAX_TARGETS)
    }

    private fun execute(command: PublishChangeRecordToGitHubCommand, operation: PublicationOperation): GitHubPublication =
        locks[Math.floorMod(command.changeRecordId.hashCode(), locks.size)].withLock {
            val record = records.requireOwnedContributor(command.changeRecordId)
            require(record.repositoryKey == command.target.repositoryKey) { "기록 저장소와 게시 대상이 다릅니다." }
            val attempt = tracking.start(record.id, command.target, operation)
            try {
                val result = if (operation == PublicationOperation.PUBLISH) publisher.publish(record, command)
                    else publisher.syncSupersession(record, command)
                tracking.finish(attempt, PublicationAttemptStatus.SUCCEEDED, null, result)
                meters.counter("intenttrace.publication.attempt", "operation", operation.name, "outcome", "SUCCEEDED").increment()
                result
            } catch (exception: RuntimeException) {
                val code = when (exception) {
                    is PullRequestRevisionMismatchException -> "HEAD_MISMATCH"
                    is ForkPullRequestUnsupportedException -> "FORK_UNSUPPORTED"
                    is GitHubRepositoryMismatchException -> "REPOSITORY_MISMATCH"
                    is GitHubPublicationContentTooLargeException -> "CONTENT_TOO_LARGE"
                    is GitHubCredentialMissingException, is GitHubCredentialConfigurationException -> "CREDENTIALS_UNAVAILABLE"
                    is GitHubRateLimitException -> "GITHUB_RATE_LIMITED"
                    is PullRequestUnavailableException -> "PULL_REQUEST_UNAVAILABLE"
                    is IllegalArgumentException, is IllegalStateException -> "INVALID_RECORD_STATE"
                    else -> "REMOTE_RESULT_UNCONFIRMED"
                }
                val status = if (code == "REMOTE_RESULT_UNCONFIRMED") PublicationAttemptStatus.RESULT_UNKNOWN else PublicationAttemptStatus.FAILED
                runCatching { tracking.finish(attempt, status, code, null) }
                meters.counter("intenttrace.publication.attempt", "operation", operation.name, "outcome", status.name).increment()
                throw exception
            }
        }

    companion object {
        private const val MAX_TARGETS = 100
    }
}
