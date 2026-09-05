package io.intenttrace.record.application

import io.intenttrace.IntentTraceApplication
import io.intenttrace.identity.domain.GitHubRepository
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
        assertTrue(records.findIntent(repository.key, targetRevision, "old.txt", 1).isEmpty())
        val checked = evidence.check(draft.id)
        assertTrue(checked.codeVerified)
        assertFalse(checked.serverExecutionVerified)
        val old = history.find(repository.key, nextRevision, "new.txt", 1).items.single()
        assertEquals(IntentMatch.ANCESTOR_UNCHANGED_FILE, old.match)
        assertFalse(old.verificationAppliesToQuery)
        val changed = history.find(repository.key, changedRevision, "new.txt", 1).items.single()
        assertEquals(IntentMatch.RELATED_UNVERIFIED, changed.match)
        val wrong = records.create(command.copy(requestId = "wrong-${UUID.randomUUID()}", codeAnchors = listOf(CodeAnchor("new.txt", null, 1, 2, "f".repeat(64)))))
        records.confirm(ConfirmChangeRecordCommand(wrong.id, wrong.version, targetRevision, snapshot))
        assertFalse(evidence.check(wrong.id).codeVerified)
        assertEquals(AnchorCheckStatus.HASH_MISMATCH, evidence.check(wrong.id).anchors.single().status)
    }

    class FakeEvidence : GitEvidenceGateway {
        override fun snapshot(repository: GitHubRepository, revision: String): GitEvidenceSnapshot {
            val path = if (revision == baseRevision) "old.txt" else "new.txt"
            val sha = if (revision == changedRevision) "f".repeat(40) else "e".repeat(40)
            val entries = mutableListOf(GitTreeEntry(path, "100644", "blob", sha))
            if (revision == nextRevision) entries += GitTreeEntry("unrelated.txt", "100644", "blob", "a".repeat(40))
            return GitEvidenceSnapshot(revision, entries.associateBy { it.path })
        }
        override fun blob(repository: GitHubRepository, sha: String): ByteArray = bytes
        override fun isAncestor(repository: GitHubRepository, ancestor: String, descendant: String): Boolean = true
    }

    @TestConfiguration
    class Configuration {
        @Bean @Primary fun evidenceGateway() = FakeEvidence()
    }

    companion object {
        private val baseRevision = "1".repeat(40)
        private val targetRevision = "2".repeat(40)
        private val nextRevision = "3".repeat(40)
        private val changedRevision = "4".repeat(40)
        private val bytes = "첫 줄\n마지막 줄\n".toByteArray()
    }
}
