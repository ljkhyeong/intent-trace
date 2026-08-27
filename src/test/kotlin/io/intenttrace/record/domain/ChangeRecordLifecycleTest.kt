package io.intenttrace.record.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChangeRecordLifecycleTest {
    @Test
    fun `작성자가 확인한 같은 스냅샷만 공개할 수 있다`() {
        val draft = draft()
        val confirmed = draft.confirm(
            author = "lim",
            immutableRevision = "b".repeat(40),
            currentSnapshotDigest = digest,
            now = Instant.parse("2026-08-27T12:01:00Z"),
        )
        val published = confirmed.publish(digest, Instant.parse("2026-08-27T12:02:00Z"))

        assertEquals(ChangeRecordStatus.PUBLISHED, published.status)
        assertEquals("b".repeat(40), published.targetRevision)
        assertEquals(2, published.version)
    }

    @Test
    fun `작성자가 아니면 초안을 확인할 수 없다`() {
        val exception = assertFailsWith<IllegalStateException> {
            draft().confirm(
                author = "teammate",
                immutableRevision = "b".repeat(40),
                currentSnapshotDigest = digest,
                now = Instant.parse("2026-08-27T12:01:00Z"),
            )
        }

        assertEquals("기록을 만든 작성자만 확인할 수 있습니다.", exception.message)
    }

    @Test
    fun `코드 스냅샷이 바뀌면 공개하지 않는다`() {
        val confirmed = draft().confirm(
            author = "lim",
            immutableRevision = "b".repeat(40),
            currentSnapshotDigest = digest,
            now = Instant.parse("2026-08-27T12:01:00Z"),
        )

        val exception = assertFailsWith<IllegalStateException> {
            confirmed.publish("c".repeat(64), Instant.parse("2026-08-27T12:02:00Z"))
        }

        assertEquals("코드 스냅샷이 달라져 검증과 판단이 오래된 상태입니다.", exception.message)
    }

    private fun draft(): ChangeRecord = ChangeRecord(
        id = UUID.randomUUID(),
        requestId = "turn-1",
        repositoryKey = "intent-trace",
        baseRevision = null,
        targetRevision = null,
        snapshotDigest = digest,
        title = "변경 의도 기록",
        requestSummary = "AI 코드의 요청과 검증을 남긴다.",
        status = ChangeRecordStatus.DRAFT,
        createdBy = "lim",
        createdAt = Instant.parse("2026-08-27T12:00:00Z"),
        confirmedAt = null,
        publishedAt = null,
        supersededBy = null,
        version = 0,
        decisions = listOf(Decision("작성자 확인 후 공개한다.", null, PurposeSource.STATED_BY_USER)),
        codeAnchors = listOf(CodeAnchor("src/App.kt", "App", 1, 5, "a".repeat(64))),
        verifications = emptyList(),
        openQuestions = emptyList(),
    )

    companion object {
        private val digest = "a".repeat(64)
    }
}
