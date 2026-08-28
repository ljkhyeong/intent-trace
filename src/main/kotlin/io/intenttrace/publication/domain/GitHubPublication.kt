package io.intenttrace.publication.domain

import io.intenttrace.identity.domain.GitHubRepository
import java.time.Instant
import java.util.UUID

data class GitHubPublication(
    val id: UUID,
    val changeRecordId: UUID,
    val target: GitHubPullRequestTarget,
    val headRevision: String,
    val checkRunId: Long,
    val checkRunUrl: String,
    val contentDigest: String,
    val publishedAt: Instant,
)

data class GitHubPullRequestTarget(
    val owner: String,
    val repository: String,
    val pullNumber: Int,
) {
    val repositoryKey: String = GitHubRepository(owner, repository).key

    init {
        require(pullNumber > 0) { "Pull Request 번호는 1 이상이어야 합니다." }
    }
}

data class GitHubCheckRun(
    val id: Long,
    val url: String,
)
