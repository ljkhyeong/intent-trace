package io.intenttrace.record.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class ChangeRecordContentTest {
    @Test
    fun `기본 기록과 후속 기록의 확장 근거 해시는 기존 값과 일치한다`() {
        val startedAt = Instant.parse("2026-09-06T00:00:00Z")
        val base = ChangeRecordContent(
            baseRevision = "1".repeat(40),
            snapshotDigest = "a".repeat(64),
            title = "해시 호환성",
            requestSummary = "첫 요청\n둘째 줄",
            decisions = listOf(Decision("기존 형식 유지", null, PurposeSource.STATED_BY_USER)),
            codeAnchors = listOf(CodeAnchor("src/한글.kt", null, 2, 3, "b".repeat(64))),
            verifications = listOf(VerificationRun("./gradlew test", 0, startedAt, startedAt.plusSeconds(2),
                "a".repeat(64), "c".repeat(64), "통과")),
            openQuestions = listOf("후속 확인"),
        )
        val extended = base.copy(
            codeAnchors = listOf(
                base.codeAnchors.single().copy(side = CodeSide.BASE, relatedPath = "src/새.kt"),
                base.codeAnchors.single().copy(relativePath = "src/새.kt", relatedPath = "src/한글.kt"),
            ),
            verifications = listOf(base.verifications.single().copy(source = VerificationSource.LOCAL_RUNNER_REPORTED)),
        )
        val originalId = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

        // 5d7df51의 구현에서 확보한 값으로 저장된 생성 요청 해시와의 호환성을 확인한다.
        assertEquals("95ad5605be6960ef2d488733101c85c344e5764a9fe01bb0da2fc0d547e33243", base.digest())
        assertEquals("a7fcaf9cbcccb72fe2d563470007ab0831f1dfcf7e1a596133af6a55afdc0018", extended.digest())
        assertEquals("0fbc092feca9aed516ef125f6dcd3049c8e033cd09ccb998d259b373503d9699", base.copy(derivedFromRecordId = originalId).digest())
        assertEquals("631ec10d0336be542251f4833e2dd531c2a23369f82dacf4a27e4dd5188dbf7e", extended.copy(derivedFromRecordId = originalId).digest())
    }
}
