package io.intenttrace.publication.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.publication.domain.GitHubCheckRun
import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.application.ChangeRecordMarkdownRenderer
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PublishChangeRecordToGitHubTest {
    private val record = publishedRecord()
    private val gateway = FakeGitHubGateway(record.targetRevision!!)
    private val publicationRepository = InMemoryGitHubPublicationRepository()
    private val publisher = PublishChangeRecordToGitHub(
        markdownRenderer = ChangeRecordMarkdownRenderer(),
        gitHubGateway = gateway,
        publicationRepository = publicationRepository,
        clock = fixedClock,
    )

    @Test
    fun `PR HEAD가 기록 커밋과 같으면 Check Run과 게시 이력을 만든다`() {
        val publication = publisher.publish(
            record,
            PublishChangeRecordToGitHubCommand(record.id, target),
        )

        assertEquals(record.targetRevision, publication.headRevision)
        assertEquals(42L, publication.checkRunId)
        assertEquals("intent-trace:${record.id}", gateway.lastCommand?.externalId)
        assertEquals(publication, publicationRepository.saved)
    }

    @Test
    fun `PR HEAD가 바뀌었으면 GitHub 쓰기를 시작하지 않는다`() {
        gateway.headRevision = "c".repeat(40)

        assertFailsWith<PullRequestRevisionMismatchException> {
            publisher.publish(record, PublishChangeRecordToGitHubCommand(record.id, target))
        }

        assertEquals(null, gateway.lastCommand)
        assertEquals(null, publicationRepository.saved)
    }

    private class FakeGitHubGateway(
        var headRevision: String,
    ) : GitHubPullRequestGateway {
        var lastCommand: UpsertGitHubCheckRunCommand? = null

        override fun getHeadRevision(target: GitHubPullRequestTarget): String = headRevision

        override fun upsertCheckRun(command: UpsertGitHubCheckRunCommand): GitHubCheckRun {
            lastCommand = command
            return GitHubCheckRun(42L, "https://github.test/check-runs/42")
        }
    }

    private class InMemoryGitHubPublicationRepository : GitHubPublicationRepository {
        var saved: GitHubPublication? = null

        override fun find(changeRecordId: UUID, target: GitHubPullRequestTarget): GitHubPublication? = saved

        override fun save(publication: GitHubPublication): GitHubPublication {
            saved = publication
            return publication
        }
    }

    companion object {
        private val fixedClock = Clock.fixed(Instant.parse("2026-08-27T15:00:00Z"), ZoneOffset.UTC)
        private val target = GitHubPullRequestTarget("acme", "intent-trace", 12)

        private fun publishedRecord(): ChangeRecord = ChangeRecord(
            id = UUID.fromString("8c766289-5c2c-4b1f-90e6-376058868c42"),
            requestId = "github-publication-test",
            repositoryKey = "acme/intent-trace",
            baseRevision = null,
            targetRevision = "b".repeat(40),
            snapshotDigest = "a".repeat(64),
            title = "GitHub PR에 변경 의도 게시",
            requestSummary = "공개 기록을 같은 커밋의 PR에 연결한다.",
            status = ChangeRecordStatus.PUBLISHED,
            createdBy = ActorIdentity.github(1, "lim"),
            createdAt = Instant.parse("2026-08-27T14:00:00Z"),
            confirmedAt = Instant.parse("2026-08-27T14:01:00Z"),
            publishedAt = Instant.parse("2026-08-27T14:02:00Z"),
            supersededBy = null,
            version = 2,
            decisions = listOf(Decision("PR HEAD를 확인한다.", null, PurposeSource.STATED_BY_USER)),
            codeAnchors = listOf(CodeAnchor("src/App.kt", "App", 1, 4, "d".repeat(64))),
            verifications = emptyList(),
            openQuestions = emptyList(),
        )
    }
}
