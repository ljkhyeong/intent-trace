package io.intenttrace.record.domain

import io.intenttrace.identity.domain.ActorIdentity
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
            actor = author,
            immutableRevision = "B".repeat(40),
            currentSnapshotDigest = digest,
            now = Instant.parse("2026-08-27T12:01:00Z"),
        )
        val published = confirmed.publish(author, digest, Instant.parse("2026-08-27T12:02:00Z"))

        assertEquals(ChangeRecordStatus.PUBLISHED, published.status)
        assertEquals("b".repeat(40), published.targetRevision)
        assertEquals(2, published.version)
    }

    @Test
    fun `작성자가 아니면 초안을 확인할 수 없다`() {
        val exception = assertFailsWith<IllegalStateException> {
            draft().confirm(
                actor = ActorIdentity.github(2, "teammate"),
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
            actor = author,
            immutableRevision = "b".repeat(40),
            currentSnapshotDigest = digest,
            now = Instant.parse("2026-08-27T12:01:00Z"),
        )

        val exception = assertFailsWith<IllegalStateException> {
            confirmed.publish(author, "c".repeat(64), Instant.parse("2026-08-27T12:02:00Z"))
        }

        assertEquals("기록과 현재 스냅샷 해시가 달라 공개할 수 없습니다.", exception.message)
    }

    @Test
    fun `파일명에 점 두 개가 있어도 저장소 상대 경로로 인정한다`() {
        val anchor = CodeAnchor("src/foo..bar.kt", null, 1, 1, "a".repeat(64))

        assertEquals("src/foo..bar.kt", anchor.relativePath)
    }

    @Test
    fun `상위 이동과 Windows 절대 경로는 코드 근거로 받지 않는다`() {
        listOf("src/../secret.txt", "C:\\Users\\lim\\secret.txt").forEach { path ->
            assertFailsWith<IllegalArgumentException> {
                CodeAnchor(path, null, 1, 1, "a".repeat(64))
            }
        }
    }

    private fun draft(): ChangeRecord = ChangeRecord(
        id = UUID.randomUUID(),
        requestId = "turn-1",
        repositoryKey = "acme/intent-trace",
        baseRevision = null,
        targetRevision = null,
        snapshotDigest = digest,
        title = "변경 의도 기록",
        requestSummary = "AI 코드의 요청과 검증을 남긴다.",
        status = ChangeRecordStatus.DRAFT,
        createdBy = author,
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
        private val author = ActorIdentity.github(1, "lim")
    }
}
