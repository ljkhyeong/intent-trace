package io.intenttrace.publication.adapter.`in`.web

import io.intenttrace.publication.application.PublishChangeRecordToGitHubCommand
import io.intenttrace.publication.application.TeamGitHubPublicationService
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import io.intenttrace.publication.application.GitHubPublicationStatus
import io.intenttrace.publication.application.RecordPublicationTarget
import io.intenttrace.publication.application.RecordPublications
import io.intenttrace.record.domain.ChangeRecordStatus
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/change-records/{recordId}")
class GitHubPublicationController(
    private val publisher: TeamGitHubPublicationService,
) {
    @GetMapping("/github-pull-requests")
    fun targets(@PathVariable recordId: UUID): RecordPublicationsResponse = RecordPublicationsResponse.from(publisher.targets(recordId))

    @GetMapping("/github-pull-request")
    fun status(@PathVariable recordId: UUID, @RequestParam owner: String, @RequestParam repository: String,
               @RequestParam pullNumber: Int): GitHubPublicationStatus =
        publisher.status(PublishChangeRecordToGitHubCommand(recordId, GitHubPullRequestTarget(owner, repository, pullNumber)))

    @PostMapping("/github-pull-request/supersession")
    fun syncSupersession(@PathVariable recordId: UUID, @Valid @RequestBody request: GitHubPublicationRequest): GitHubPublicationResponse =
        GitHubPublicationResponse.from(publisher.syncSupersession(request.toCommand(recordId)))

    @PostMapping("/github-pull-request")
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
    val codeAnnotations: Boolean = false,
) {
    fun toCommand(recordId: UUID): PublishChangeRecordToGitHubCommand = PublishChangeRecordToGitHubCommand(
        changeRecordId = recordId,
        target = GitHubPullRequestTarget(owner, repository, pullNumber),
        codeAnnotations = codeAnnotations,
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

data class RecordPublicationsResponse(
    val recordId: UUID,
    val status: ChangeRecordStatus,
    val supersededBy: UUID?,
    val items: List<RecordPublicationTarget>,
    val truncated: Boolean,
) {
    companion object {
        fun from(result: RecordPublications): RecordPublicationsResponse = RecordPublicationsResponse(
            result.record.id, result.record.status, result.record.supersededBy, result.items, result.truncated,
        )
    }
}
