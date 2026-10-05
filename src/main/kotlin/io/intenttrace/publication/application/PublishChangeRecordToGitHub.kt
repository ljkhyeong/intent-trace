package io.intenttrace.publication.application

import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.application.ChangeRecordMarkdownRenderer
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeSide
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Service
class PublishChangeRecordToGitHub(
    private val markdownRenderer: ChangeRecordMarkdownRenderer,
    private val gitHubGateway: GitHubPullRequestGateway,
    private val publicationRepository: GitHubPublicationRepository,
    private val clock: Clock,
) {
    fun publish(record: ChangeRecord, command: PublishChangeRecordToGitHubCommand): GitHubPublication {
        return send(record, command, supersession = false)
    }

    fun syncSupersession(record: ChangeRecord, command: PublishChangeRecordToGitHubCommand): GitHubPublication {
        return send(record, command, supersession = true)
    }

    private fun send(record: ChangeRecord, command: PublishChangeRecordToGitHubCommand, supersession: Boolean): GitHubPublication {
        require(record.id == command.changeRecordId) { "GitHub 게시 명령과 변경 의도 기록이 일치하지 않습니다." }
        check(record.status == if (supersession) ChangeRecordStatus.SUPERSEDED else ChangeRecordStatus.PUBLISHED) {
            "기록 상태가 GitHub 게시 또는 대체 안내 작업과 일치하지 않습니다."
        }

        val target = command.target
        if (record.repositoryKey != target.repositoryKey) {
            throw GitHubRepositoryMismatchException(record.repositoryKey, target.repositoryKey)
        }

        val markdown = markdownRenderer.render(record)
        if (markdown.length > MAX_GITHUB_OUTPUT_LENGTH) {
            throw GitHubPublicationContentTooLargeException()
        }

        val recordRevision = checkNotNull(record.targetRevision) { "변경 의도 기록에 Git 커밋 ID가 없습니다." }
        val previous = publicationRepository.find(record.id, target)
        check(!supersession || previous != null) { "대체 안내를 반영할 GitHub 게시 이력이 없습니다." }

        val pullRequestRevision = gitHubGateway.getHeadRevision(target).lowercase()
        if (!supersession && recordRevision != pullRequestRevision) {
            throw PullRequestRevisionMismatchException(recordRevision, pullRequestRevision)
        }

        val checkCommand = UpsertGitHubCheckRunCommand(
            target = target,
            headRevision = recordRevision,
            externalId = "intent-trace:${record.id}",
            knownCheckRunId = previous?.checkRunId,
            title = if (supersession) "대체됨: ${record.title}" else record.title,
            summary = if (supersession) "새 기록으로 대체됐습니다. 본문의 후속 기록을 확인하세요." else "작성자가 확인한 IntentTrace 변경 의도 기록입니다.",
            markdown = markdown,
            annotations = if (!supersession && command.codeAnnotations) codeAnnotations(record) else emptyList(),
        )
        val checkRun = if (supersession) gitHubGateway.updateExistingCheckRun(checkCommand) else gitHubGateway.upsertCheckRun(checkCommand)

        return publicationRepository.save(
            GitHubPublication(
                id = previous?.id ?: UUID.randomUUID(),
                changeRecordId = record.id,
                target = target,
                headRevision = recordRevision,
                checkRunId = checkRun.id,
                checkRunUrl = checkRun.url,
                contentDigest = sha256(markdown),
                publishedAt = Instant.now(clock),
            ),
        )
    }

    // 게시 커밋은 PR HEAD와 같으므로 변경 후 근거만 PR 줄에 연결된다. GitHub는 요청당 주석 50개까지 받는다.
    private fun codeAnnotations(record: ChangeRecord): List<CheckRunAnnotation> {
        val title = markdownRenderer.annotationTitle(record)
        val message = markdownRenderer.annotationMessage(record)
        return record.codeAnchors.filter { it.side == CodeSide.TARGET }.take(MAX_CODE_ANNOTATIONS)
            .map { CheckRunAnnotation(it.relativePath, it.startLine, it.endLine, title, message) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .let(HexFormat.of()::formatHex)

    companion object {
        private const val MAX_GITHUB_OUTPUT_LENGTH = 65_535
        private const val MAX_CODE_ANNOTATIONS = 50
    }
}

data class PublishChangeRecordToGitHubCommand(
    val changeRecordId: UUID,
    val target: GitHubPullRequestTarget,
    val codeAnnotations: Boolean = false,
)
