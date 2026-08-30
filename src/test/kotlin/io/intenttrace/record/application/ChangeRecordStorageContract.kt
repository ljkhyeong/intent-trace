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
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs

abstract class ChangeRecordStorageContract {
    @Autowired
    private lateinit var storageFacade: ChangeRecordFacade

    @Autowired
    private lateinit var storageJdbc: JdbcTemplate

    @Test
    fun `DB가 반올림해 저장한 검증 시각도 같은 요청으로 재사용한다`() {
        val startedAt = Instant.parse("2026-08-30T00:00:00.123456789Z")
        val command = command().copy(
            verifications = listOf(
                VerificationRun("./gradlew test", 0, startedAt, startedAt.plusSeconds(1), digest, digest, "테스트용 검증"),
            ),
        )

        val first = storageFacade.create(command, actor)
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
