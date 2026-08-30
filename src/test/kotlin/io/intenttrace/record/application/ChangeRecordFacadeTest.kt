package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChangeRecordFacadeTest {
    @Test
    fun `동시에 같은 요청이 저장되면 먼저 저장된 기록을 재사용한다`() {
        val existing = record()
        val repository = DuplicateRequestRepository(existing)
        val facade = ChangeRecordFacade(repository, SensitiveTextRedactor(), fixedClock)

        val result = facade.create(command(), actor)

        assertEquals(existing.id, result.id)
        assertEquals(2, repository.findByRequestIdCount)
    }

    @Test
    fun `같은 요청 식별자의 저장 내용이 다르면 충돌로 처리한다`() {
        val repository = DuplicateRequestRepository(record())
        val facade = ChangeRecordFacade(repository, SensitiveTextRedactor(), fixedClock)

        assertFailsWith<ChangeRecordRequestConflictException> {
            facade.create(command().copy(title = "다른 변경 의도"), actor)
        }
    }

    @Test
    fun `같은 요청 식별자를 다른 사용자가 재사용하면 충돌로 처리한다`() {
        val repository = DuplicateRequestRepository(record())
        val facade = ChangeRecordFacade(repository, SensitiveTextRedactor(), fixedClock)

        assertFailsWith<ChangeRecordRequestConflictException> {
            facade.create(command(), ActorIdentity.github(2, "teammate"))
        }
    }

    @Test
    fun `같은 요청 식별자를 다른 저장소가 재사용하면 충돌로 처리한다`() {
        val repository = DuplicateRequestRepository(record())
        val facade = ChangeRecordFacade(repository, SensitiveTextRedactor(), fixedClock)

        assertFailsWith<ChangeRecordRequestConflictException> {
            facade.create(command().copy(repositoryKey = "acme/other"), actor)
        }
    }

    private class DuplicateRequestRepository(
        private val existing: ChangeRecord,
    ) : ChangeRecordRepository {
        var findByRequestIdCount = 0

        override fun findSummaries(
            repositoryKey: String,
            statuses: Set<ChangeRecordStatus>,
            authorSubject: String?,
            relativePath: String?,
            pageable: Pageable,
        ): Slice<ChangeRecordSummary> = error("사용하지 않는 테스트 경로")

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
        ): List<ChangeRecord> = emptyList()

        override fun saveNew(record: ChangeRecord): ChangeRecord =
            throw DuplicateKeyException("request_id unique 제약 충돌")

        override fun update(record: ChangeRecord, expectedVersion: Long): ChangeRecord =
            error("사용하지 않는 테스트 경로")
    }

    companion object {
        private val actor = ActorIdentity.github(1, "lim")
        private val fixedClock = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC)

        private fun command() = CreateChangeRecordCommand(
            requestId = "concurrent-request",
            repositoryKey = "Acme/Intent-Trace",
            snapshotDigest = "a".repeat(64),
            title = "동시 요청",
            requestSummary = "같은 요청을 한 번만 저장한다.",
            decisions = listOf(Decision("DB unique 제약으로 판정한다.", null, PurposeSource.STATED_BY_USER)),
            codeAnchors = listOf(CodeAnchor("src/App.kt", "App", 1, 2, "b".repeat(64))),
            verifications = emptyList(),
            openQuestions = emptyList(),
        )

        private fun record() = ChangeRecord(
            id = UUID.randomUUID(),
            requestId = "concurrent-request",
            repositoryKey = "acme/intent-trace",
            targetRevision = null,
            snapshotDigest = "a".repeat(64),
            title = "동시 요청",
            requestSummary = "같은 요청을 한 번만 저장한다.",
            status = ChangeRecordStatus.DRAFT,
            createdBy = actor,
            createdAt = Instant.parse("2026-08-29T00:00:00Z"),
            confirmedAt = null,
            publishedAt = null,
            supersededBy = null,
            version = 0,
            decisions = listOf(Decision("DB unique 제약으로 판정한다.", null, PurposeSource.STATED_BY_USER)),
            codeAnchors = listOf(CodeAnchor("src/App.kt", "App", 1, 2, "b".repeat(64))),
            verifications = emptyList(),
            openQuestions = emptyList(),
        )
    }
}
