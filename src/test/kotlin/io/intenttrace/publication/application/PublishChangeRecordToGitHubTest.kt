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
import kotlin.test.assertTrue
import io.intenttrace.record.application.TeamChangeRecordService
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun `동시 최초 게시는 한 Check Run으로 모으고 응답 유실도 같은 실행으로 복구한다`() {
        val records = mock(TeamChangeRecordService::class.java)
        `when`(records.requireOwnedContributor(record.id)).thenReturn(record)
        `when`(records.get(record.id)).thenReturn(record)
        val tracking = MemoryTracking()
        val team = TeamGitHubPublicationService(records, publisher, tracking, publicationRepository)
        val command = PublishChangeRecordToGitHubCommand(record.id, target)
        gateway.failAfterCreate = true
        assertFailsWith<GitHubApiException> { team.publish(command) }
        assertEquals(PublicationAttemptStatus.RESULT_UNKNOWN, tracking.statuses.values.single())
        gateway.failAfterCreate = false
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val calls = (1..2).map { executor.submit<GitHubPublication> { start.await(); team.publish(command) } }
            start.countDown()
            assertEquals(setOf(42L), calls.map { it.get(5, TimeUnit.SECONDS).checkRunId }.toSet())
            assertEquals(1, gateway.creations)
            assertEquals(2, tracking.statuses.values.count { it == PublicationAttemptStatus.SUCCEEDED })
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `PR HEAD가 진행돼도 기존 커밋의 Check Run에 대체 안내를 붙인다`() {
        publisher.publish(record, PublishChangeRecordToGitHubCommand(record.id, target))
        val replacement = UUID.randomUUID()
        gateway.headRevision = "f".repeat(40)
        val result = publisher.syncSupersession(record.copy(status = ChangeRecordStatus.SUPERSEDED, supersededBy = replacement),
            PublishChangeRecordToGitHubCommand(record.id, target))
        assertEquals(42L, result.checkRunId)
        assertEquals(record.targetRevision, gateway.lastCommand?.headRevision)
        assertTrue(gateway.lastCommand!!.markdown.contains(replacement.toString()))
        assertEquals(1, gateway.creations)
    }

    private class MemoryTracking : GitHubPublicationTracking {
        val statuses = linkedMapOf<UUID, PublicationAttemptStatus>()
        override fun start(recordId: UUID, target: GitHubPullRequestTarget, operation: PublicationOperation): UUID =
            UUID.randomUUID().also { statuses[it] = PublicationAttemptStatus.IN_PROGRESS }
        override fun finish(attemptId: UUID, status: PublicationAttemptStatus, failureCode: String?, publication: GitHubPublication?) { statuses[attemptId] = status }
        override fun recent(recordId: UUID, target: GitHubPullRequestTarget): List<PublicationAttempt> = emptyList()
    }

    private class FakeGitHubGateway(
        var headRevision: String,
    ) : GitHubPullRequestGateway {
        var lastCommand: UpsertGitHubCheckRunCommand? = null
        var failAfterCreate = false
        var creations = 0

        override fun getHeadRevision(target: GitHubPullRequestTarget): String = headRevision

        override fun upsertCheckRun(command: UpsertGitHubCheckRunCommand): GitHubCheckRun {
            lastCommand = command
            if (creations == 0) creations++
            if (failAfterCreate) throw GitHubApiException("원격 결과를 확인하지 못했습니다.")
            return GitHubCheckRun(42L, "https://github.test/check-runs/42")
        }

        override fun updateExistingCheckRun(command: UpsertGitHubCheckRunCommand): GitHubCheckRun = upsertCheckRun(command)
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
