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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun `같은 기록과 PR의 동시 게시는 최초 Check Run을 한 번만 만든다`() {
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val results = (1..2).map {
                executor.submit<GitHubPublication> {
                    ready.countDown()
                    start.await()
                    publisher.publish(record, PublishChangeRecordToGitHubCommand(record.id, target))
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            results.forEach { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, gateway.initialUpserts.get())
            assertEquals(listOf(null, 42L), gateway.commands.map { it.knownCheckRunId })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `게시 내용이 너무 크면 GitHub 조회 전에 거부한다`() {
        val oversized = record.copy(title = "가".repeat(65_536))

        assertFailsWith<GitHubPublicationContentTooLargeException> {
            publisher.publish(oversized, PublishChangeRecordToGitHubCommand(record.id, target))
        }

        assertEquals(0, gateway.headRequests.get())
        assertEquals(0, gateway.commands.size)
    }

    @Test
    fun `비공개 기록과 다른 저장소는 GitHub 조회 전에 거부한다`() {
        assertFailsWith<IllegalStateException> {
            publisher.publish(
                record.copy(status = ChangeRecordStatus.AUTHOR_CONFIRMED),
                PublishChangeRecordToGitHubCommand(record.id, target),
            )
        }
        assertFailsWith<GitHubRepositoryMismatchException> {
            publisher.publish(
                record,
                PublishChangeRecordToGitHubCommand(
                    record.id,
                    GitHubPullRequestTarget("acme", "other", 12),
                ),
            )
        }

        assertEquals(0, gateway.headRequests.get())
        assertEquals(0, gateway.commands.size)
    }

    private class FakeGitHubGateway(
        var headRevision: String,
    ) : GitHubPullRequestGateway {
        val headRequests = AtomicInteger()
        val initialUpserts = AtomicInteger()
        val commands = CopyOnWriteArrayList<UpsertGitHubCheckRunCommand>()
        var lastCommand: UpsertGitHubCheckRunCommand? = null

        override fun getHeadRevision(target: GitHubPullRequestTarget): String {
            headRequests.incrementAndGet()
            return headRevision
        }

        override fun upsertCheckRun(command: UpsertGitHubCheckRunCommand): GitHubCheckRun {
            lastCommand = command
            commands += command
            if (command.knownCheckRunId == null) {
                initialUpserts.incrementAndGet()
                Thread.sleep(100)
            }
            return GitHubCheckRun(42L, "https://github.test/check-runs/42")
        }
    }

    private class InMemoryGitHubPublicationRepository : GitHubPublicationRepository {
        @Volatile var saved: GitHubPublication? = null

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
