package io.intenttrace.publication.application

import io.intenttrace.config.GitHubApiException
import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.publication.domain.GitHubCheckRun
import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.application.ChangeRecordMarkdownRenderer
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import io.intenttrace.record.domain.draftRecord
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.intenttrace.record.application.TeamChangeRecordService
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.Executors
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class PublishChangeRecordToGitHubTest {
    private val record = draftRecord(ActorIdentity.github(1, "lim")).copy(
        status = ChangeRecordStatus.PUBLISHED, targetRevision = "b".repeat(40), title = "GitHub PR에 변경 의도 게시",
        decisions = listOf(Decision("PR HEAD를 확인한다.", null, PurposeSource.STATED_BY_USER)),
    )
    private val gateway = FakeGitHubGateway(record.targetRevision!!)
    private val publicationRepository = InMemoryGitHubPublicationRepository()
    private val publisher = PublishChangeRecordToGitHub(
        markdownRenderer = ChangeRecordMarkdownRenderer(GitHubProperties()),
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
        val sent = gateway.commands.last()
        assertEquals("intent-trace:${record.id}", sent.externalId)
        assertEquals(publication, publicationRepository.find(record.id, target))
        assertTrue(sent.markdown.contains("등록된 검증 결과가 없습니다."))
        assertEquals(emptyList(), sent.annotations)
    }

    @Test
    fun `코드 주석을 요청하면 변경 후 근거에만 결정 요약 주석을 50개까지 만든다`() {
        val anchors = listOf(CodeAnchor("src/Old.kt", null, 2, 3, "e".repeat(64), side = CodeSide.BASE)) +
            (1..51).map { CodeAnchor("src/App$it.kt", null, it, it + 1, "d".repeat(64)) }
        val annotated = record.copy(baseRevision = "a".repeat(40), codeAnchors = anchors)

        publisher.publish(annotated, PublishChangeRecordToGitHubCommand(record.id, target, codeAnnotations = true))

        val annotations = gateway.commands.last().annotations
        assertEquals((1..50).map { "src/App$it.kt" }, annotations.map { it.path })
        assertEquals(
            CheckRunAnnotation(
                "src/App1.kt", 1, 2, "변경 의도: GitHub PR에 변경 의도 게시",
                "구현 결정과 이유\n- PR HEAD를 확인한다. — 사용자가 명시함\n요청·관련 코드·검증 결과는 이 Check Run 상세에서 확인하세요.",
            ),
            annotations.first(),
        )
    }

    @Test
    fun `PR HEAD가 바뀌었으면 GitHub 쓰기를 시작하지 않는다`() {
        gateway.headRevision = "c".repeat(40)

        assertFailsWith<PullRequestRevisionMismatchException> {
            publisher.publish(record, PublishChangeRecordToGitHubCommand(record.id, target))
        }

        assertTrue(gateway.commands.isEmpty())
        assertEquals(null, publicationRepository.find(record.id, target))
    }

    @ParameterizedTest
    @ValueSource(ints = [12, 13])
    fun `같은 기록의 동시 게시는 PR이 같거나 달라도 Check Run을 한 번만 만든다`(secondPullNumber: Int) {
        val team = teamPublisher()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        gateway.beforeUpsert = {
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
        }
        val secondTarget = target.copy(pullNumber = secondPullNumber)
        val first = FutureTask { team.publish(PublishChangeRecordToGitHubCommand(record.id, target)) }
        val second = FutureTask { team.publish(PublishChangeRecordToGitHubCommand(record.id, secondTarget)) }
        val firstThread = Thread(first)
        val secondThread = Thread(second)
        try {
            firstThread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            secondThread.start()
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                while (secondThread.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)) Thread.sleep(1)
            }
            assertEquals(1, gateway.commands.size)
        } finally {
            proceed.countDown()
            firstThread.join(5_000)
            secondThread.join(5_000)
        }

        assertEquals(42L, first.get(5, TimeUnit.SECONDS).checkRunId)
        assertEquals(42L, second.get(5, TimeUnit.SECONDS).checkRunId)
        assertEquals(1, gateway.initialUpserts.get())
        assertEquals(
            listOf(null, if (secondTarget == target) 42L else null),
            gateway.commands.map { it.knownCheckRunId },
        )
        assertEquals(secondTarget, publicationRepository.find(record.id, secondTarget)?.target)
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
        val tracking = MemoryTracking()
        assertFailsWith<IllegalArgumentException> {
            teamPublisher(tracking).publish(PublishChangeRecordToGitHubCommand(record.id, GitHubPullRequestTarget("acme", "other", 12)))
        }

        assertTrue(tracking.statuses.isEmpty())
        assertEquals(0, gateway.headRequests.get())
        assertEquals(0, gateway.commands.size)
    }

    @Test
    fun `결과 미확인 뒤 동시 재시도는 한 Check Run으로 모은다`() {
        val tracking = MemoryTracking()
        val team = teamPublisher(tracking)
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
            assertEquals(2, tracking.statuses.values.count { it == PublicationAttemptStatus.SUCCEEDED })
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `GitHub에 쓰기 전 PR 조회 실패는 결과 미확인이 아닌 실패로 남긴다`() {
        val tracking = MemoryTracking()
        gateway.headFailure = GitHubApiException("GitHub Pull Request 조회 요청이 실패했습니다. HTTP 404")

        val failure = assertFailsWith<PullRequestUnavailableException> { teamPublisher(tracking).publish(PublishChangeRecordToGitHubCommand(record.id, target)) }

        assertTrue(failure.message.orEmpty().startsWith("GitHub Pull Request 조회 요청이 실패했습니다. HTTP 404"))
        assertEquals(PublicationAttemptStatus.FAILED, tracking.statuses.values.single())
        assertEquals("PULL_REQUEST_UNAVAILABLE", tracking.codes.values.single())
        assertTrue(gateway.commands.isEmpty())
    }

    @Test
    fun `게시 이력이 없는 대체 안내는 GitHub 조회 전에 거부한다`() {
        val superseded = record.copy(status = ChangeRecordStatus.SUPERSEDED, supersededBy = UUID.randomUUID())

        assertFailsWith<IllegalStateException> {
            publisher.syncSupersession(superseded, PublishChangeRecordToGitHubCommand(record.id, target))
        }

        assertEquals(0, gateway.headRequests.get())
        assertTrue(gateway.commands.isEmpty())
    }

    @Test
    fun `PR HEAD가 진행돼도 기존 커밋의 Check Run에 대체 안내를 붙인다`() {
        publisher.publish(record, PublishChangeRecordToGitHubCommand(record.id, target))
        val replacement = UUID.randomUUID()
        gateway.headRevision = "f".repeat(40)
        val result = publisher.syncSupersession(record.copy(status = ChangeRecordStatus.SUPERSEDED, supersededBy = replacement),
            PublishChangeRecordToGitHubCommand(record.id, target, codeAnnotations = true))
        assertEquals(42L, result.checkRunId)
        val sent = gateway.commands.last()
        assertEquals(emptyList(), sent.annotations)
        assertEquals(record.targetRevision, sent.headRevision)
        assertTrue(sent.markdown.contains(replacement.toString()))
    }

    private fun teamPublisher(tracking: MemoryTracking = MemoryTracking()): TeamGitHubPublicationService {
        val records = mock(TeamChangeRecordService::class.java)
        `when`(records.requireOwnedContributor(record.id)).thenReturn(record)
        return TeamGitHubPublicationService(records, publisher, tracking, publicationRepository, SimpleMeterRegistry())
    }

    private class MemoryTracking : GitHubPublicationTracking {
        val statuses = linkedMapOf<UUID, PublicationAttemptStatus>()
        val codes = linkedMapOf<UUID, String?>()
        override fun start(recordId: UUID, target: GitHubPullRequestTarget, operation: PublicationOperation): UUID =
            UUID.randomUUID().also { statuses[it] = PublicationAttemptStatus.IN_PROGRESS }
        override fun finish(attemptId: UUID, status: PublicationAttemptStatus, failureCode: String?, publication: GitHubPublication?) {
            statuses[attemptId] = status
            codes[attemptId] = failureCode
        }
        override fun recent(recordId: UUID, target: GitHubPullRequestTarget): List<PublicationAttempt> = emptyList()
        override fun latest(recordIds: Collection<UUID>, target: GitHubPullRequestTarget): Map<UUID, PublicationAttempt> = emptyMap()
        override fun latestByTarget(recordId: UUID, limit: Int): List<PublicationTargetAttempt> = emptyList()
    }

    private class FakeGitHubGateway(
        var headRevision: String,
    ) : GitHubPullRequestGateway {
        val headRequests = AtomicInteger()
        val initialUpserts = AtomicInteger()
        val commands = CopyOnWriteArrayList<UpsertGitHubCheckRunCommand>()
        var failAfterCreate = false
        var headFailure: GitHubApiException? = null
        var beforeUpsert: () -> Unit = {}
        @Volatile private var checkRun: GitHubCheckRun? = null

        override fun getHeadRevision(target: GitHubPullRequestTarget): String {
            headRequests.incrementAndGet()
            headFailure?.let { throw it }
            return headRevision
        }

        override fun upsertCheckRun(command: UpsertGitHubCheckRunCommand): GitHubCheckRun {
            if (failAfterCreate) throw GitHubApiException("원격 결과를 확인하지 못했습니다.")
            commands += command
            val existing = checkRun
            beforeUpsert()
            if (existing != null) return existing
            val id = 42L + initialUpserts.getAndIncrement()
            return GitHubCheckRun(id, "https://github.test/check-runs/$id").also { checkRun = it }
        }

        override fun updateExistingCheckRun(command: UpsertGitHubCheckRunCommand): GitHubCheckRun = upsertCheckRun(command)
    }

    private class InMemoryGitHubPublicationRepository : GitHubPublicationRepository {
        private val records = ConcurrentHashMap<Pair<UUID, GitHubPullRequestTarget>, GitHubPublication>()

        override fun find(changeRecordId: UUID, target: GitHubPullRequestTarget): GitHubPublication? =
            records[changeRecordId to target]

        override fun findAll(changeRecordIds: Collection<UUID>, target: GitHubPullRequestTarget): Map<UUID, GitHubPublication> =
            changeRecordIds.mapNotNull { id -> find(id, target)?.let { id to it } }.toMap()

        override fun save(publication: GitHubPublication): GitHubPublication {
            records[publication.changeRecordId to publication.target] = publication
            return publication
        }

        override fun findByRecord(changeRecordId: UUID, limit: Int): List<GitHubPublication> =
            records.values.filter { it.changeRecordId == changeRecordId }.take(limit)
    }

    companion object {
        private val fixedClock = Clock.fixed(Instant.parse("2026-08-27T15:00:00Z"), ZoneOffset.UTC)
        private val target = GitHubPullRequestTarget("acme", "intent-trace", 12)
    }
}
