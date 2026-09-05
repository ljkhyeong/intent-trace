package io.intenttrace.publication.application

import io.intenttrace.identity.application.RepositoryAccessService
import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.application.ChangeRecordCatalogService
import io.intenttrace.record.application.ChangeRecordSummary
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

data class PullRequestSnapshot(val headRevision: String, val fork: Boolean)

interface GitHubPullRequestReader {
    fun read(target: GitHubPullRequestTarget): PullRequestSnapshot
}

data class PullRequestRecord(
    val record: ChangeRecordSummary,
    val matchesCurrentHead: Boolean,
    val publication: GitHubPublication?,
    val latestAttempt: PublicationAttempt?,
)

data class PullRequestOverview(
    val repositoryKey: String, val pullNumber: Int, val headRevision: String, val fork: Boolean,
    val checkedAt: Instant, val items: List<PullRequestRecord>, val nextCursor: String?,
)

@Service
class PullRequestOverviewService(
    private val access: RepositoryAccessService,
    private val reader: GitHubPullRequestReader,
    private val catalog: ChangeRecordCatalogService,
    private val publications: GitHubPublicationRepository,
    private val tracking: GitHubPublicationTracking,
    private val clock: Clock,
) {
    fun overview(target: GitHubPullRequestTarget, cursor: String? = null, limit: Int = 20): PullRequestOverview {
        require(limit in 1..100) { "PR 기록 목록 크기는 1~100이어야 합니다." }
        access.requireReader(target.repositoryKey)
        val pr = reader.read(target)
        val page = catalog.list(target.repositoryKey, cursor = cursor, limit = limit, pullNumber = target.pullNumber)
        return PullRequestOverview(target.repositoryKey, target.pullNumber, pr.headRevision, pr.fork, Instant.now(clock),
            page.items.map { record -> PullRequestRecord(record, record.targetRevision == pr.headRevision,
                publications.find(record.id, target), tracking.recent(record.id, target).firstOrNull()) }, page.nextCursor)
    }
}
