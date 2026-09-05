package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import io.intenttrace.record.domain.VerificationRun
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

abstract class ChangeRecordStorageContract {
    @Autowired
    private lateinit var storageFacade: ChangeRecordFacade

    @Autowired
    private lateinit var storageJdbc: JdbcTemplate

    @Autowired
    private lateinit var storageRepository: ChangeRecordRepository

    @Test
    fun `파일 이력과 내 초안을 페이지로 조회한다`() {
        val repositoryKey = "acme/history-${UUID.randomUUID()}"
        fun draft(owner: ActorIdentity = actor, path: String = "src/Storage.kt"): ChangeRecord =
            storageFacade.create(
                command().copy(
                    repositoryKey = repositoryKey,
                    codeAnchors = listOf(
                        CodeAnchor(path, "Storage", 1, 2, digest),
                        CodeAnchor(path, "Storage", 5, 6, digest),
                    ),
                ),
                owner,
            )
        fun confirm(record: ChangeRecord, revision: String = "b".repeat(40)): ChangeRecord =
            storageFacade.confirm(ConfirmChangeRecordCommand(record.id, record.version, revision, digest), actor)
        fun publish(record: ChangeRecord): ChangeRecord =
            storageFacade.publish(PublishChangeRecordCommand(record.id, record.version, digest), actor)

        val ownDraft = draft()
        val ownConfirmed = confirm(draft())
        repeat(3) { draft(ActorIdentity.github(2, "teammate")) }
        draft(path = "src/Other.kt")
        val old = publish(confirm(draft()))
        val replacement = publish(confirm(draft(), "c".repeat(40)))
        storageFacade.supersede(SupersedeChangeRecordCommand(old.id, old.version, replacement.id), actor)
        // 같은 시각에 만든 기록도 UUID 보조 정렬로 페이지가 겹치지 않아야 한다.
        storageJdbc.update(
            "update change_records set created_at = ? where repository_key = ?",
            Instant.parse("2026-08-30T00:00:00Z").atOffset(ZoneOffset.UTC), repositoryKey,
        )

        val privateQuery = ListChangeRecordsQuery(
            repositoryKey.uppercase(), ChangeRecordListScope.MY_DRAFTS, "src/./Storage.kt", size = 1,
        )
        val first = storageFacade.list(privateQuery, actor)
        val second = storageFacade.list(privateQuery.copy(page = 1), actor)
        assertTrue(first.hasNext())
        assertFalse(second.hasNext())
        assertEquals(
            listOf(ownDraft.id, ownConfirmed.id).map(UUID::toString).sortedDescending(),
            (first.content + second.content).map { it.id.toString() },
        )
        assertTrue(storageFacade.list(privateQuery.copy(page = 2), actor).isEmpty)
        assertEquals(
            listOf(ownConfirmed.id),
            storageFacade.list(privateQuery.copy(status = ChangeRecordStatus.AUTHOR_CONFIRMED), actor).content.map { it.id },
        )

        val publicQuery = ListChangeRecordsQuery(repositoryKey, path = "src/Storage.kt")
        val history = storageFacade.list(publicQuery, ActorIdentity.github(2, "teammate")).content
        assertEquals(setOf(old.id, replacement.id), history.map { it.id }.toSet())
        assertEquals(2, history.size)
        assertEquals(replacement.id, history.single { it.id == old.id }.supersededBy)
        assertEquals(setOf("b".repeat(40), "c".repeat(40)), history.map { it.targetRevision }.toSet())
        assertEquals(
            listOf(replacement.id),
            storageFacade.list(publicQuery.copy(status = ChangeRecordStatus.PUBLISHED), actor).content.map { it.id },
        )
    }

    @Test
    fun `DB에 저장한 생성 확인 공개 검증 시각이 응답과 일치한다`() {
        val startedAt = Instant.parse("2026-08-30T00:00:00.123456789Z")
        val command = command().copy(
            verifications = listOf(
                VerificationRun("./gradlew test", 0, startedAt, startedAt.plusSeconds(1), digest, digest, "테스트용 검증"),
            ),
        )

        val preciseFacade = ChangeRecordFacade(storageRepository, SensitiveTextRedactor(), Clock.fixed(startedAt, ZoneOffset.UTC))
        val first = preciseFacade.create(command, actor)
        // 정규화가 없던 이전 저장 방식도 같은 요청으로 인식해야 한다.
        storageJdbc.update(
            "update verification_runs set started_at = ?, finished_at = ? where record_id = ?",
            startedAt.atOffset(ZoneOffset.UTC),
            startedAt.plusSeconds(1).atOffset(ZoneOffset.UTC),
            first.id.toString(),
        )
        val retried = storageFacade.create(command, actor)

        assertEquals(first.id, retried.id)
        assertEquals(Instant.parse("2026-08-30T00:00:00.123457Z"), first.verifications.single().startedAt)
        assertEquals(first.verifications, retried.verifications)
        assertEquals(first, storageFacade.get(first.id))
        val confirmed = preciseFacade.confirm(ConfirmChangeRecordCommand(first.id, first.version, "b".repeat(40), digest), actor)
        assertEquals(confirmed, storageFacade.get(first.id))
        val published = preciseFacade.publish(PublishChangeRecordCommand(first.id, confirmed.version, digest), actor)
        assertEquals(published, storageFacade.get(first.id))
    }

    @Test
    fun `서로 반대 방향의 동시 대체는 하나만 성공한다`() {
        val first = published()
        val second = published()
        val start = CyclicBarrier(2)
        val commands = listOf(
            SupersedeChangeRecordCommand(first.id, first.version, second.id),
            SupersedeChangeRecordCommand(second.id, second.version, first.id),
        )

        val results = Executors.newFixedThreadPool(2).use { executor ->
            commands.map { command ->
                executor.submit<Result<ChangeRecord>> {
                    start.await(5, TimeUnit.SECONDS)
                    runCatching { storageFacade.supersede(command, actor) }
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
        }

        assertEquals(1, results.count { it.isSuccess })
        assertIs<IllegalStateException>(results.single { it.isFailure }.exceptionOrNull())
        val superseded = results.single { it.isSuccess }.getOrThrow()
        val stored = storageFacade.get(superseded.id)
        assertEquals(ChangeRecordStatus.SUPERSEDED, stored.status)
        assertEquals(ChangeRecordStatus.PUBLISHED, storageFacade.get(requireNotNull(stored.supersededBy)).status)
    }

    private fun published(): ChangeRecord {
        val draft = storageFacade.create(command(), actor)
        val confirmed = storageFacade.confirm(
            ConfirmChangeRecordCommand(draft.id, draft.version, "b".repeat(40), digest),
            actor,
        )
        return storageFacade.publish(PublishChangeRecordCommand(confirmed.id, confirmed.version, digest), actor)
    }

    private fun command() = CreateChangeRecordCommand(
        requestId = "storage-${UUID.randomUUID()}",
        repositoryKey = "acme/storage-contract",
        snapshotDigest = digest,
        title = "저장 계약 검증",
        requestSummary = "DB 왕복과 동시 갱신을 확인한다.",
        decisions = listOf(Decision("저장 계약을 유지한다.", null, PurposeSource.STATED_BY_USER)),
        codeAnchors = listOf(CodeAnchor("src/Storage.kt", "Storage", 1, 2, digest)),
        verifications = emptyList(),
        openQuestions = emptyList(),
    )

    companion object {
        private val actor = ActorIdentity.github(1, "storage-test")
        private val digest = "a".repeat(64)
    }
}
