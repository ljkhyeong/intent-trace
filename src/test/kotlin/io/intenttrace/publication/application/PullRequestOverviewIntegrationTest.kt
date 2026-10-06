package io.intenttrace.publication.application

import io.intenttrace.IntentTraceApplication
import io.intenttrace.connection.application.ConnectionDiagnostics
import io.intenttrace.connection.application.DiagnosticStatus
import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.application.*
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.PurposeSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant
import java.util.UUID
import kotlin.test.*

@SpringBootTest(
    classes = [IntentTraceApplication::class, DraftManagementIntegrationTest.Configuration::class, PullRequestOverviewIntegrationTest.Configuration::class],
    properties = ["spring.datasource.url=jdbc:h2:mem:pr-overview;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"],
)
class PullRequestOverviewIntegrationTest(
    @Autowired private val records: TeamChangeRecordService,
    @Autowired private val publications: GitHubPublicationRepository,
    @Autowired private val tracking: GitHubPublicationTracking,
    @Autowired private val overview: PullRequestOverviewService,
    @Autowired private val diagnostics: ConnectionDiagnostics,
    @Autowired private val publisher: TeamGitHubPublicationService,
) {
    @MockitoSpyBean
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `PR 목록은 실패한 게시와 이전 커밋을 포함하고 비공개 기록과 다른 PR을 제외한다`() {
        val target = GitHubPullRequestTarget("Acme", "Overview", 12)
        fun record(revision: String, publish: Boolean = true) = records.create(CreateChangeRecordCommand(
            UUID.randomUUID().toString(), target.repositoryKey, null, "a".repeat(64), "PR 변경", "게시 기록을 확인한다.",
            listOf(Decision("같은 PR의 기록을 조회한다.", null, PurposeSource.STATED_BY_USER)),
            listOf(CodeAnchor("app.kt", null, 1, 1, "a".repeat(64))), emptyList(), emptyList(),
        )).let {
            if (!publish) it else {
                records.confirm(ConfirmChangeRecordCommand(it.id, 0, revision, it.snapshotDigest))
                records.publish(PublishChangeRecordCommand(it.id, 1, it.snapshotDigest))
            }
        }
        val old = record("1".repeat(40))
        tracking.start(old.id, target, PublicationOperation.PUBLISH)
            .also { tracking.finish(it, PublicationAttemptStatus.SUCCEEDED, null, null) }
        publications.save(GitHubPublication(UUID.randomUUID(), old.id, target, old.targetRevision!!, 8,
            "https://github.com/acme/overview/runs/8", "a".repeat(64), Instant.now()))
        val current = record(head)
        val attempt = tracking.start(current.id, target, PublicationOperation.PUBLISH)
        tracking.finish(attempt, PublicationAttemptStatus.RESULT_UNKNOWN, "NETWORK_FAILURE", null)
        val private = record(head, false)
        tracking.start(private.id, target, PublicationOperation.PUBLISH)
        tracking.start(record(head).id, target.copy(pullNumber = 13), PublicationOperation.PUBLISH)
        val first = overview.overview(target, limit = 1)
        val second = overview.overview(target, cursor = assertNotNull(first.nextCursor), limit = 1)
        assertNull(second.nextCursor)
        val results = first.items + second.items
        assertEquals(setOf(old.id, current.id), results.map { it.record.id }.toSet())
        assertFalse(results.single { it.record.id == old.id }.matchesCurrentHead)
        assertTrue(results.single { it.record.id == current.id }.matchesCurrentHead)
        assertNull(results.single { it.record.id == current.id }.publication)
        assertEquals(PublicationAttemptStatus.RESULT_UNKNOWN, results.single { it.record.id == current.id }.latestAttempt?.status)

        repeat(18) { index ->
            val extra = record(head)
            publications.save(GitHubPublication(UUID.randomUUID(), extra.id, target, head, 100L + index,
                "https://github.com/acme/overview/runs/${100 + index}", "a".repeat(64), Instant.now()))
            tracking.start(extra.id, target, PublicationOperation.PUBLISH)
        }
        clearInvocations(jdbc)
        val page = overview.overview(target, limit = 20)
        assertEquals(20, page.items.size)
        assertEquals(19, page.items.count { it.publication != null })
        assertEquals(20, page.items.count { it.latestAttempt != null })
        assertEquals(3, queryCount())

        clearInvocations(jdbc)
        assertTrue(overview.overview(target.copy(pullNumber = 99)).items.isEmpty())
        assertEquals(1, queryCount())
    }

    private fun queryCount(): Int = mockingDetails(jdbc).invocations.count {
        it.method.name == "query" && it.method.parameterCount == 3 &&
            it.method.parameterTypes[0] == PreparedStatementCreator::class.java
    }

    @Test
    fun `기록별 게시 목록은 게시와 시도를 합치고 대체된 기록의 미반영 PR을 표시한다`() {
        val target = GitHubPullRequestTarget("Acme", "RecordTargets", 21)
        fun record() = records.create(CreateChangeRecordCommand(
            UUID.randomUUID().toString(), target.repositoryKey, null, "a".repeat(64), "게시 대상", "게시한 PR을 모은다.",
            listOf(Decision("기록에서 PR을 찾는다.", null, PurposeSource.STATED_BY_USER)),
            listOf(CodeAnchor("app.kt", null, 1, 1, "a".repeat(64))), emptyList(), emptyList(),
        )).let {
            records.confirm(ConfirmChangeRecordCommand(it.id, 0, head, it.snapshotDigest))
            records.publish(PublishChangeRecordCommand(it.id, 1, it.snapshotDigest))
        }
        val original = record()
        assertTrue(publisher.targets(original.id).items.isEmpty())
        for (pullNumber in listOf(21, 22)) {
            publications.save(GitHubPublication(UUID.randomUUID(), original.id, target.copy(pullNumber = pullNumber), head,
                pullNumber.toLong(), "https://github.com/acme/recordtargets/runs/$pullNumber", "a".repeat(64), Instant.now()))
        }
        tracking.start(original.id, target.copy(pullNumber = 23), PublicationOperation.PUBLISH)
            .also { tracking.finish(it, PublicationAttemptStatus.FAILED, "PULL_REQUEST_UNAVAILABLE", null) }
        val replacement = record()
        records.supersede(SupersedeChangeRecordCommand(original.id, original.version, replacement.id))
        tracking.start(original.id, target, PublicationOperation.SUPERSESSION_NOTICE)
            .also { tracking.finish(it, PublicationAttemptStatus.SUCCEEDED, null, null) }

        val result = publisher.targets(original.id)

        assertEquals(ChangeRecordStatus.SUPERSEDED, result.record.status)
        assertFalse(result.truncated)
        val items = result.items.associateBy { it.pullNumber }
        assertEquals(setOf(21, 22, 23), items.keys)
        assertTrue(items.values.all { it.repositoryKey == "acme/recordtargets" })
        assertFalse(items.getValue(21).supersessionNoticeNeeded)
        assertTrue(items.getValue(22).supersessionNoticeNeeded)
        assertFalse(items.getValue(23).supersessionNoticeNeeded)
        assertNull(items.getValue(23).publication)
        assertEquals(PublicationAttemptStatus.FAILED, items.getValue(23).latestAttempt?.status)
    }

    @Test
    fun `진단은 확인한 권한과 확인하지 않은 코드 및 게시 설정을 구분한다`() {
        val checks = diagnostics.diagnose("acme/overview").checks.associateBy { it.name }
        assertEquals(DiagnosticStatus.VERIFIED, checks.getValue("repository_read").status)
        assertEquals(DiagnosticStatus.NOT_CHECKED, checks.getValue("git_tree_read").status)
        assertEquals(DiagnosticStatus.NOT_CONFIGURED, checks.getValue("publication_credentials").status)

        val moved = diagnostics.diagnose("acme/overview", pullNumber = MOVED_PULL_NUMBER).checks.associateBy { it.name }
        assertEquals(DiagnosticStatus.FAILED, moved.getValue("pull_request_read").status)
        assertContains(moved.getValue("pull_request_read").message, "병합 대상 저장소")
        assertEquals(DiagnosticStatus.NOT_CHECKED, moved.getValue("git_tree_read").status)
        assertEquals(DiagnosticStatus.NOT_CONFIGURED, moved.getValue("publication_credentials").status)
    }

    @TestConfiguration
    class Configuration {
        @Bean @Primary fun pullRequestReader() = object : GitHubPullRequestReader {
            override fun read(target: GitHubPullRequestTarget) =
                if (target.pullNumber == MOVED_PULL_NUMBER) throw GitHubRepositoryMismatchException(target.repositoryKey, "acme/renamed")
                else PullRequestSnapshot(head, false)
        }
    }

    companion object {
        private val head = "2".repeat(40)
        private const val MOVED_PULL_NUMBER = 77
    }
}
