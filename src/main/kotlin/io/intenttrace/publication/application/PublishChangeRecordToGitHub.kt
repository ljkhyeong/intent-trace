package io.intenttrace.publication.application

import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.application.ChangeRecordMarkdownRenderer
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
class PublishChangeRecordToGitHub(
    private val markdownRenderer: ChangeRecordMarkdownRenderer,
    private val gitHubGateway: GitHubPullRequestGateway,
    private val publicationRepository: GitHubPublicationRepository,
    private val clock: Clock,
) {
    private val publicationLocks = Array(PUBLICATION_LOCK_STRIPES) { ReentrantLock() }

    fun publish(record: ChangeRecord, command: PublishChangeRecordToGitHubCommand): GitHubPublication {
        require(record.id == command.changeRecordId) { "GitHub 게시 명령과 변경 의도 기록이 일치하지 않습니다." }
        check(record.status == ChangeRecordStatus.PUBLISHED) {
            "공개 상태의 변경 의도 기록만 GitHub에 게시할 수 있습니다."
        }

        val target = command.target
        if (record.repositoryKey != target.repositoryKey) {
            throw GitHubRepositoryMismatchException(record.repositoryKey, target.repositoryKey)
        }

        val markdown = markdownRenderer.render(record)
        if (markdown.length > MAX_GITHUB_OUTPUT_LENGTH) {
            throw GitHubPublicationContentTooLargeException()
        }

        return publicationLocks[Math.floorMod(record.id.hashCode(), publicationLocks.size)].withLock {
            publishLocked(record, target, markdown)
        }
    }

    private fun publishLocked(
        record: ChangeRecord,
        target: GitHubPullRequestTarget,
        markdown: String,
    ): GitHubPublication {
        val recordRevision = checkNotNull(record.targetRevision) { "변경 의도 기록에 Git 커밋 ID가 없습니다." }
        val pullRequestRevision = gitHubGateway.getHeadRevision(target).lowercase()
        if (recordRevision != pullRequestRevision) {
            throw PullRequestRevisionMismatchException(recordRevision, pullRequestRevision)
        }

        val previous = publicationRepository.find(record.id, target)
        val checkRun = gitHubGateway.upsertCheckRun(
            UpsertGitHubCheckRunCommand(
                target = target,
                headRevision = recordRevision,
                externalId = "intent-trace:${record.id}",
                knownCheckRunId = previous?.checkRunId,
                title = record.title,
                summary = "작성자가 확인한 IntentTrace 변경 의도 기록입니다.",
                markdown = markdown,
            ),
        )

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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .let(HexFormat.of()::formatHex)

    companion object {
        private const val MAX_GITHUB_OUTPUT_LENGTH = 65_535
        private const val PUBLICATION_LOCK_STRIPES = 256
    }
}

data class PublishChangeRecordToGitHubCommand(
    val changeRecordId: UUID,
    val target: GitHubPullRequestTarget,
)
