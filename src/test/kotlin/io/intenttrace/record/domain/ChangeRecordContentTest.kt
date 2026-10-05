package io.intenttrace.record.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ChangeRecordContentTest {
    @Test
    fun `같은 내용은 같은 해시이고 근거 쪽·연결 경로·검증 출처·원본 기록이 다르면 해시가 달라진다`() {
        val startedAt = Instant.parse("2026-09-06T00:00:00Z")
        val base = ChangeRecordContent(
            baseRevision = "1".repeat(40),
            snapshotDigest = "a".repeat(64),
            title = "해시",
            requestSummary = "첫 요청\n둘째 줄",
            decisions = listOf(Decision("내용 해시", null, PurposeSource.STATED_BY_USER)),
            codeAnchors = listOf(CodeAnchor("src/한글.kt", null, 2, 3, "b".repeat(64))),
            verifications = listOf(VerificationRun("./gradlew test", 0, startedAt, startedAt.plusSeconds(2),
                "a".repeat(64), "c".repeat(64), "통과")),
            openQuestions = listOf("후속 확인"),
        )
        assertEquals(base.digest(), base.copy().digest())
        val anchor = base.codeAnchors.single()
        for (changed in listOf(
            base.copy(codeAnchors = listOf(anchor.copy(side = CodeSide.BASE))),
            base.copy(codeAnchors = listOf(anchor.copy(relatedPath = "src/새.kt"))),
            base.copy(verifications = listOf(base.verifications.single().copy(source = VerificationSource.LOCAL_RUNNER_REPORTED))),
            base.copy(derivedFromRecordId = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")),
            base.copy(openQuestions = emptyList()),
            // 필드 경계가 섞여도 다른 내용으로 구분한다.
            base.copy(title = "해시첫", requestSummary = " 요청\n둘째 줄"),
        )) assertNotEquals(base.digest(), changed.digest())
    }
}
