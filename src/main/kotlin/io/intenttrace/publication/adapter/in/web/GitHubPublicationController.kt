package io.intenttrace.publication.adapter.`in`.web

import io.intenttrace.publication.application.PublishChangeRecordToGitHub
import io.intenttrace.publication.application.PublishChangeRecordToGitHubCommand
import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/change-records/{recordId}/github-pull-request")
class GitHubPublicationController(
    private val publisher: PublishChangeRecordToGitHub,
) {
    @PostMapping
    fun publish(
        @PathVariable recordId: UUID,
        @Valid @RequestBody request: GitHubPublicationRequest,
    ): GitHubPublicationResponse = GitHubPublicationResponse.from(
        publisher.publish(request.toCommand(recordId)),
    )
}

data class GitHubPublicationRequest(
    @field:NotBlank @field:Size(max = 100)
    @field:Pattern(regexp = "^[A-Za-z0-9_.-]+$")
    val owner: String,
    @field:NotBlank @field:Size(max = 100)
    @field:Pattern(regexp = "^[A-Za-z0-9_.-]+$")
    val repository: String,
    @field:Min(1)
    val pullNumber: Int,
) {
    fun toCommand(recordId: UUID): PublishChangeRecordToGitHubCommand = PublishChangeRecordToGitHubCommand(
        changeRecordId = recordId,
        target = GitHubPullRequestTarget(owner, repository, pullNumber),
    )
}

data class GitHubPublicationResponse(
    val id: UUID,
    val changeRecordId: UUID,
    val repository: String,
    val pullNumber: Int,
    val headRevision: String,
    val checkRunId: Long,
    val checkRunUrl: String,
    val contentDigest: String,
    val publishedAt: Instant,
) {
    companion object {
        fun from(publication: GitHubPublication): GitHubPublicationResponse = GitHubPublicationResponse(
            id = publication.id,
            changeRecordId = publication.changeRecordId,
            repository = publication.target.repositoryKey,
            pullNumber = publication.target.pullNumber,
            headRevision = publication.headRevision,
            checkRunId = publication.checkRunId,
            checkRunUrl = publication.checkRunUrl,
            contentDigest = publication.contentDigest,
            publishedAt = publication.publishedAt,
        )
    }
}
