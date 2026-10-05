package io.intenttrace.publication.application

import io.intenttrace.publication.domain.GitHubCheckRun
import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import java.util.UUID

interface GitHubPullRequestGateway {
    fun getHeadRevision(target: GitHubPullRequestTarget): String

    fun upsertCheckRun(command: UpsertGitHubCheckRunCommand): GitHubCheckRun

    fun updateExistingCheckRun(command: UpsertGitHubCheckRunCommand): GitHubCheckRun
}

data class UpsertGitHubCheckRunCommand(
    val target: GitHubPullRequestTarget,
    val headRevision: String,
    val externalId: String,
    val knownCheckRunId: Long?,
    val title: String,
    val summary: String,
    val markdown: String,
    val annotations: List<CheckRunAnnotation> = emptyList(),
)

/** 변경 후 관련 코드에 붙이는 Check Run 줄 주석이다. */
data class CheckRunAnnotation(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val title: String,
    val message: String,
)

interface GitHubPublicationRepository {
    fun find(changeRecordId: UUID, target: GitHubPullRequestTarget): GitHubPublication?

    fun findAll(changeRecordIds: Collection<UUID>, target: GitHubPullRequestTarget): Map<UUID, GitHubPublication>

    fun save(publication: GitHubPublication): GitHubPublication
}
