package io.intenttrace.publication.domain

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
    init {
        require(REPOSITORY_PART.matches(owner)) { "GitHub 저장소 소유자 형식이 올바르지 않습니다." }
        require(REPOSITORY_PART.matches(repository) && !repository.endsWith(".git")) {
            "GitHub 저장소 이름 형식이 올바르지 않습니다."
        }
        require(pullNumber > 0) { "Pull Request 번호는 1 이상이어야 합니다." }
    }

    val repositoryKey: String = "$owner/$repository"

    companion object {
        private val REPOSITORY_PART = Regex("^[A-Za-z0-9_.-]{1,100}$")
    }
}

data class GitHubCheckRun(
    val id: Long,
    val url: String,
)
