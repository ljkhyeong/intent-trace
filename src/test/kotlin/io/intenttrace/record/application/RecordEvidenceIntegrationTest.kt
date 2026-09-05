package io.intenttrace.record.application

import io.intenttrace.IntentTraceApplication
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.record.adapter.`in`.web.ChangeRecordResponse
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import io.intenttrace.record.domain.VerificationRun
import io.intenttrace.record.domain.VerificationSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.config.GitHubRateLimitException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [IntentTraceApplication::class, DraftManagementIntegrationTest.Configuration::class, RecordEvidenceIntegrationTest.Configuration::class],
    properties = ["spring.datasource.url=jdbc:h2:mem:record-evidence;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"],
)
class RecordEvidenceIntegrationTest(
    @Autowired private val records: TeamChangeRecordService,
    @Autowired private val evidence: RecordEvidenceService,
    @Autowired private val history: ChangeIntentHistoryService,
    @Autowired private val gateway: FakeEvidence,
    @Autowired private val catalog: ChangeRecordCatalogService,
    @Autowired private val facade: ChangeRecordFacade,
    @Autowired private val access: io.intenttrace.identity.application.RepositoryAccessService,
) {
    @Test
    fun `변경 전 삭제 근거를 저장하고 해시 불일치와 이전 커밋 검증을 구분한다`() {
        val repository = GitHubRepository.parse("acme/evidence")
        val snapshot = gateway.snapshot(repository, targetRevision).digest
        val anchorHash = GitEvidenceDigest.sha256(bytes)
        val command = CreateChangeRecordCommand(
            "evidence-${UUID.randomUUID()}", repository.key, baseRevision, snapshot, "파일 이름 변경", "기존 파일을 새 이름으로 이동한다.",
            listOf(Decision("이전 경로를 보존한다.", null, PurposeSource.STATED_BY_USER)),
            listOf(CodeAnchor("old.txt", null, 1, 2, anchorHash, CodeSide.BASE, "new.txt"),
                CodeAnchor("new.txt", null, 1, 2, anchorHash, CodeSide.TARGET, "old.txt")),
            listOf(VerificationRun("test", 0, Instant.EPOCH, Instant.EPOCH, snapshot, "d".repeat(64), "로컬 수집", VerificationSource.LOCAL_RUNNER_REPORTED)),
            emptyList(),
        )
        val draft = records.create(command)
        val confirmed = records.confirm(ConfirmChangeRecordCommand(draft.id, draft.version, targetRevision, snapshot))
        val published = records.publish(PublishChangeRecordCommand(draft.id, confirmed.version, snapshot))
        assertEquals(CodeSide.BASE, records.get(draft.id).codeAnchors.first().side)
        assertEquals(VerificationSource.LOCAL_RUNNER_REPORTED, records.get(draft.id).verifications.first().source)
        assertEquals(listOf(published.id), records.findIntent(repository.key, baseRevision, "old.txt", 1).map { it.id })
        assertFalse(ChangeRecordResponse.from(published, baseRevision).verifications.single().current)
        assertTrue(ChangeRecordResponse.from(published, targetRevision).verifications.single().current)
        assertTrue(records.findIntent(repository.key, targetRevision, "old.txt", 1).isEmpty())
        val checked = evidence.check(draft.id)
        assertTrue(checked.codeVerified)
        assertFalse(checked.serverExecutionVerified)
        val related = history.find(repository.key, nextRevision, "new.txt", 1).items
        assertEquals(IntentMatch.ANCESTOR_RENAMED_FILE, related.single { it.side == CodeSide.BASE }.match)
        val old = related.single { it.side == CodeSide.TARGET }
        assertEquals(IntentMatch.ANCESTOR_UNCHANGED_FILE, old.match)
        assertFalse(old.verificationAppliesToQuery)
        val changed = history.find(repository.key, changedRevision, "new.txt", 1).items.single()
        assertEquals(IntentMatch.RELATED_UNVERIFIED, changed.match)
        val moved = history.find(repository.key, movedRevision, "new.txt", 2).items.single()
        assertEquals(IntentMatch.ANCESTOR_MOVED_LINES, moved.match)
        assertEquals(2, moved.currentStartLine)
        assertEquals(3, moved.currentEndLine)
        assertFalse(moved.verificationAppliesToQuery)
        val wrong = records.create(command.copy(requestId = "wrong-${UUID.randomUUID()}", codeAnchors = listOf(CodeAnchor("new.txt", null, 1, 2, "f".repeat(64)))))
        records.confirm(ConfirmChangeRecordCommand(wrong.id, wrong.version, targetRevision, snapshot))
        assertFalse(evidence.check(wrong.id).codeVerified)
        assertEquals(AnchorCheckStatus.HASH_MISMATCH, evidence.check(wrong.id).anchors.single().status)
    }

    @Test
    fun `후보 실패와 재조회를 구분하고 공유 Git 객체는 한 번 읽되 인증 실패는 중단한다`() {
        val repo = "acme/partial-history"
        val badRevision = "6".repeat(40)
        fun create(revision: String) = records.create(CreateChangeRecordCommand(
            UUID.randomUUID().toString(), repo, null, "a".repeat(64), "과거 기록", "후보를 확인한다.",
            listOf(Decision("근거 보존", null, PurposeSource.STATED_BY_USER)),
            listOf(CodeAnchor("new.txt", null, 1, 2, GitEvidenceDigest.sha256(bytes))), emptyList(), emptyList(),
        )).let { draft ->
            val confirmed = records.confirm(ConfirmChangeRecordCommand(draft.id, draft.version, revision, draft.snapshotDigest))
            records.publish(PublishChangeRecordCommand(draft.id, confirmed.version, draft.snapshotDigest))
        }
        val good = listOf(create(targetRevision), create(targetRevision))
        val bad = create(badRevision)
        gateway.snapshotCalls.clear(); gateway.blobCalls = 0; gateway.ancestryCalls = 0
        gateway.failure = badRevision to EvidenceUnavailableException(EvidenceUnavailableReason.SIZE_LIMIT)
        try {
            val result = history.find(repo, nextRevision, "new.txt", 1)
            assertEquals(good.map { it.id }.toSet(), result.items.map { it.record.id }.toSet())
            assertFalse(result.complete)
            assertEquals(listOf(HistoryCandidateFailure(bad.id, EvidenceUnavailableReason.SIZE_LIMIT)), result.failures)
            assertEquals(1, gateway.snapshotCalls[targetRevision])
            assertEquals(1, gateway.snapshotCalls[nextRevision])
            assertEquals(1, gateway.blobCalls)
            assertEquals(1, gateway.ancestryCalls)
            gateway.failure = null
            val retried = history.find(repo, nextRevision, "new.txt", 1, retryRecordId = bad.id)
            assertTrue(retried.complete)
            assertEquals(listOf(bad.id), retried.items.map { it.record.id })
            assertEquals(1, retried.scannedRecords)
            assertFailsWith<ChangeRecordNotFoundException> { history.find("acme/another", nextRevision, "new.txt", 1, retryRecordId = bad.id) }
            for (failure in listOf(GitHubUserAuthenticationException(), GitHubApiException("HTTP 403"), GitHubRateLimitException(60))) {
                gateway.failure = badRevision to failure
                assertSame(failure, assertFailsWith<RuntimeException> { history.find(repo, nextRevision, "new.txt", 1) })
            }
        } finally { gateway.failure = null }
    }

    @Test
    fun `조회 중단 후 같은 기록의 미완료 근거부터 재개하고 다음 후보도 빠짐없이 읽는다`() {
        val repo = "acme/resume-history"
        fun publish(title: String) = records.create(CreateChangeRecordCommand(
            UUID.randomUUID().toString(), repo, nextRevision, "a".repeat(64), title, "중단한 근거부터 이어 읽는다.",
            listOf(Decision("근거 보존", null, PurposeSource.STATED_BY_USER)),
            listOf(CodeAnchor("new.txt", null, 1, 2, GitEvidenceDigest.sha256(bytes), CodeSide.BASE),
                CodeAnchor("new.txt", null, 1, 2, GitEvidenceDigest.sha256(bytes))), emptyList(), emptyList(),
        )).let {
            val confirmed = records.confirm(ConfirmChangeRecordCommand(it.id, it.version, targetRevision, it.snapshotDigest))
            records.publish(PublishChangeRecordCommand(it.id, confirmed.version, it.snapshotDigest))
        }
        val older = publish("다음 페이지의 기록")
        val newer = publish("중단할 기록")
        gateway.failure = targetRevision to EvidenceReadStopped(HistoryStopReason.TIME_LIMIT)
        val service = ChangeIntentHistoryService(catalog, facade, access, gateway, HistoryReadPolicy(java.time.Duration.ofSeconds(30), 40))
        try {
            val first = service.find(repo, nextRevision, "new.txt", 1, limit = 1)
            assertFalse(first.complete)
            assertEquals(HistoryStopReason.TIME_LIMIT, first.stopReason)
            assertEquals(listOf(newer.id to CodeSide.BASE), first.items.map { it.record.id to it.side })
            gateway.failure = null
            val resumed = service.find(repo, nextRevision, "new.txt", 1, cursor = first.nextCursor)
            assertTrue(resumed.complete)
            assertEquals(listOf(newer.id to CodeSide.TARGET), resumed.items.map { it.record.id to it.side })
            val next = service.find(repo, nextRevision, "new.txt", 1, cursor = resumed.nextCursor)
            assertEquals(setOf(older.id), next.items.map { it.record.id }.toSet())
            assertEquals(null, next.nextCursor)
            assertFailsWith<IllegalArgumentException> { service.find(repo, nextRevision, "new.txt", 2, cursor = first.nextCursor) }
            val badCursor = HistoryResumeCursor(HistoryResumeCursor.queryDigest("acme/another", nextRevision, "new.txt", 1),
                RecordCursor(newer.createdAt, newer.id), 1, 1, false).encode()
            assertFailsWith<ChangeRecordNotFoundException> { service.find("acme/another", nextRevision, "new.txt", 1, cursor = badCursor) }
        } finally { gateway.failure = null }
    }

    class FakeEvidence : GitEvidenceGateway {
        var failure: Pair<String, RuntimeException>? = null
        val snapshotCalls = mutableMapOf<String, Int>()
        var blobCalls = 0
        var ancestryCalls = 0
        override fun snapshot(repository: GitHubRepository, revision: String, budget: EvidenceReadBudget?): GitEvidenceSnapshot {
            snapshotCalls[revision] = (snapshotCalls[revision] ?: 0) + 1
            failure?.takeIf { it.first == revision }?.let { throw it.second }
            val path = if (revision == baseRevision) "old.txt" else "new.txt"
            val sha = when (revision) { changedRevision -> "f".repeat(40); movedRevision -> "d".repeat(40); else -> "e".repeat(40) }
            val entries = mutableListOf(GitTreeEntry(path, "100644", "blob", sha))
            if (revision == nextRevision) entries += GitTreeEntry("unrelated.txt", "100644", "blob", "a".repeat(40))
            return GitEvidenceSnapshot(revision, entries.associateBy { it.path })
        }
        override fun blob(repository: GitHubRepository, sha: String, budget: EvidenceReadBudget?): ByteArray {
            blobCalls++
            return when (sha) {
            "f".repeat(40) -> "바뀐 코드\n".toByteArray()
            "d".repeat(40) -> "추가한 줄\n".toByteArray() + bytes
            else -> bytes
        }
        }
        override fun isAncestor(repository: GitHubRepository, ancestor: String, descendant: String, budget: EvidenceReadBudget?): Boolean { ancestryCalls++; return true }
    }

    @TestConfiguration
    class Configuration {
        @Bean @Primary fun evidenceGateway() = FakeEvidence()
    }

    companion object {
        private val baseRevision = "1".repeat(40)
        private val targetRevision = "2".repeat(40)
        private val nextRevision = "3".repeat(40)
        private val movedRevision = "5".repeat(40)
        private val changedRevision = "4".repeat(40)
        private val bytes = "첫 줄\n마지막 줄\n".toByteArray()
    }
}
