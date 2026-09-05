package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

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

    private class DuplicateRequestRepository(
        private val existing: ChangeRecord,
    ) : ChangeRecordRepository {
        var findByRequestIdCount = 0

        override fun findById(id: UUID): ChangeRecord? = null

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

        override fun update(record: ChangeRecord, expectedVersion: Long, activity: RecordActivity): ChangeRecord =
            error("사용하지 않는 테스트 경로")
    }

    companion object {
        private val actor = ActorIdentity.github(1, "lim")
        private val fixedClock = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC)

        private fun command() = CreateChangeRecordCommand(
            requestId = "concurrent-request",
            repositoryKey = "Acme/Intent-Trace",
            baseRevision = null,
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
            baseRevision = null,
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
