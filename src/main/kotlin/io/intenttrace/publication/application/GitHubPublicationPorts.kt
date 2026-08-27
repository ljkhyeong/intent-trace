package io.intenttrace.publication.application

import io.intenttrace.publication.domain.GitHubCheckRun
import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import java.util.UUID

interface GitHubPullRequestGateway {
    fun getHeadRevision(target: GitHubPullRequestTarget): String

    fun upsertCheckRun(command: UpsertGitHubCheckRunCommand): GitHubCheckRun
}

data class UpsertGitHubCheckRunCommand(
    val target: GitHubPullRequestTarget,
    val headRevision: String,
    val externalId: String,
    val knownCheckRunId: Long?,
    val title: String,
    val summary: String,
    val markdown: String,
)

interface GitHubPublicationRepository {
    fun find(changeRecordId: UUID, target: GitHubPullRequestTarget): GitHubPublication?

    fun save(publication: GitHubPublication): GitHubPublication
}
