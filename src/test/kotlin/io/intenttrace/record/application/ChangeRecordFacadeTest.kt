package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordContent
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.intenttrace.record.domain.draftRecord

class ChangeRecordFacadeTest {
    @Test
    fun `동시에 같은 요청이 저장되면 먼저 저장된 기록을 재사용한다`() {
        val existing = record()
        val repository = DuplicateRequestRepository(existing)
        val facade = ChangeRecordFacade(repository, SensitiveTextRedactor(), fixedClock, SimpleMeterRegistry())

        val result = facade.create(command(), actor)

        assertEquals(existing.id, result.id)
        assertEquals(2, repository.findByRequestIdCount)
    }

    @Test
    fun `같은 요청 식별자의 저장 내용이 다르면 충돌로 처리한다`() {
        val repository = DuplicateRequestRepository(record())
        val facade = ChangeRecordFacade(repository, SensitiveTextRedactor(), fixedClock, SimpleMeterRegistry())

        val exception = assertFailsWith<ChangeRecordRequestConflictException> {
            facade.create(command().copy(title = "다른 변경 의도"), actor)
        }

        assertFalse(exception.message.orEmpty().contains("concurrent-request"))
    }

    @Test
    fun `같은 요청 식별자를 다른 사용자가 재사용하면 충돌로 처리한다`() {
        val repository = DuplicateRequestRepository(record())
        val facade = ChangeRecordFacade(repository, SensitiveTextRedactor(), fixedClock, SimpleMeterRegistry())

        assertFailsWith<ChangeRecordRequestConflictException> {
            facade.create(command(), ActorIdentity.github(2, "teammate"))
        }
    }

    @Test
    fun `같은 요청 식별자를 다른 저장소가 재사용하면 충돌로 처리한다`() {
        val repository = DuplicateRequestRepository(record())
        val facade = ChangeRecordFacade(repository, SensitiveTextRedactor(), fixedClock, SimpleMeterRegistry())

        assertFailsWith<ChangeRecordRequestConflictException> {
            facade.create(command().copy(repositoryKey = "acme/other"), actor)
        }
    }

    private class DuplicateRequestRepository(
        private val existing: ChangeRecord,
    ) : ChangeRecordRepository {
        var findByRequestIdCount = 0

        override fun findById(id: UUID): ChangeRecord? = null

        override fun findByIdsForUpdate(ids: Set<UUID>): List<ChangeRecord> =
            error("사용하지 않는 테스트 경로")

        override fun findByRequestId(requestId: String): ChangeRecord? {
            findByRequestIdCount += 1
            return existing.takeIf { findByRequestIdCount > 1 && it.requestId == requestId }
        }

        override fun findPublishedByAnchor(
            repositoryKey: String,
            targetRevision: String,
            relativePath: String,
            line: Int,
            limit: Int,
        ): List<ChangeRecord> = emptyList()

        override fun saveNew(record: ChangeRecord): ChangeRecord =
            throw DuplicateKeyException("request_id unique 제약 충돌")

        override fun update(record: ChangeRecord, expectedVersion: Long, activity: RecordActivity): ChangeRecord =
            error("사용하지 않는 테스트 경로")
    }

    companion object {
        private val actor = ActorIdentity.github(1, "lim")
        private val fixedClock = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC)

        private fun command() = createCommand("Acme/Intent-Trace", requestId = "concurrent-request")

        // 같은 요청의 재시도는 생성 명령과 같은 내용 해시로 판정한다.
        private fun record() = command().let {
            draftRecord(actor).copy(requestId = it.requestId, creationDigest = ChangeRecordContent(it.baseRevision, it.snapshotDigest, it.title,
                it.requestSummary, it.decisions, it.codeAnchors, it.verifications, it.openQuestions, it.derivedFromRecordId).digest())
        }
    }
}
