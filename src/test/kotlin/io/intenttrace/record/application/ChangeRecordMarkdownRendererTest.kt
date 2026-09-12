package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import io.intenttrace.record.domain.VerificationRun
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ChangeRecordMarkdownRendererTest {
    private val renderer = ChangeRecordMarkdownRenderer()

    private fun record(): ChangeRecord {
        return ChangeRecord(
            id = UUID.fromString("8c766289-5c2c-4b1f-90e6-376058868c42"),
            requestId = "markdown-test",
            repositoryKey = "acme/intent-trace",
            baseRevision = "c".repeat(40),
            targetRevision = "b".repeat(40),
            snapshotDigest = "a".repeat(64),
            title = "# 가짜 제목",
            requestSummary = "[가짜 링크](https://example.test)\n## 주입된 제목",
            status = ChangeRecordStatus.PUBLISHED,
            createdBy = ActorIdentity.github(42, "lim"),
            createdAt = Instant.parse("2026-08-27T14:00:00Z"),
            confirmedAt = Instant.parse("2026-08-27T14:01:00Z"),
            publishedAt = Instant.parse("2026-08-27T14:02:00Z"),
            supersededBy = null,
            version = 2,
            decisions = listOf(Decision("- 새 목록", "**강조된 근거**", PurposeSource.STATED_BY_USER)),
            codeAnchors = listOf(
                CodeAnchor("src/Strange`Name.kt", "`symbol`", 1, 2, "c".repeat(64), relatedPath = "src/Old`Name.kt"),
                CodeAnchor("src/Old`Name.kt", null, 1, 2, "c".repeat(64), side = CodeSide.BASE, relatedPath = "src/Strange`Name.kt"),
            ),
            verifications = listOf(
                VerificationRun(
                    command = "echo `pwd`\n./gradlew test",
                    exitCode = 0,
                    startedAt = Instant.parse("2026-08-27T13:58:00Z"),
                    finishedAt = Instant.parse("2026-08-27T13:59:00Z"),
                    snapshotDigest = "a".repeat(64),
                    outputDigest = "d".repeat(64),
                    summary = "> 성공처럼 보이는 인용",
                ),
            ),
            openQuestions = listOf("# 확인할 질문"),
        )
    }

    @Test
    fun `외부 문장은 Markdown 구조를 만들지 않고 백틱이 있는 코드는 그대로 표시한다`() {
        val markdown = renderer.render(record())

        assertContains(markdown, "# 변경 의도: \\# 가짜 제목")
        assertContains(markdown, "\\[가짜 링크\\]\\(https\\:\\/\\/example\\.test\\) \\#\\# 주입된 제목")
        assertContains(markdown, "- \\- 새 목록 — 사용자가 명시함")
        assertContains(markdown, "결정 이유: \\*\\*강조된 근거\\*\\*")
        assertContains(markdown, "``src/Strange`Name.kt:1-2``")
        assertContains(markdown, "(`` `symbol` ``)")
        assertContains(markdown, "변경 전 파일 경로: ``src/Old`Name.kt``")
        assertContains(markdown, "변경 후 파일 경로: ``src/Strange`Name.kt``")
        assertContains(markdown, "``echo `pwd` ./gradlew test``")
        assertContains(markdown, "— \\> 성공처럼 보이는 인용")
        assertContains(markdown, "- \\# 확인할 질문")
        assertFalse(markdown.contains("\n## 주입된 제목"))
    }

    @ParameterizedTest
    @CsvSource("true,0,통과", "true,1,실패", "false,0,다른 스냅샷의 결과", "false,2,다른 스냅샷의 결과")
    fun `검증 상태와 함께 실행 시각과 종료 코드 및 검증 대상을 내보낸다`(
        current: Boolean,
        exitCode: Int,
        state: String,
    ) {
        val record = record()
        val verification = record.verifications.single().copy(
            exitCode = exitCode,
            snapshotDigest = if (current) record.snapshotDigest else "e".repeat(64),
        )

        val markdown = renderer.render(record.copy(verifications = listOf(verification)))

        assertContains(markdown, "- **$state**")
        assertContains(markdown, "종료 코드: `$exitCode`")
        assertContains(markdown, "실행 시각(UTC): `2026-08-27T13:58:00Z` → `2026-08-27T13:59:00Z`")
        assertContains(markdown, "검증 스냅샷 해시: `${verification.snapshotDigest}`")
        assertContains(markdown, "출력 해시: `${verification.outputDigest}`")
        assertContains(markdown, "출처: 클라이언트가 제출함")
        assertContains(markdown, "서버는 테스트 실행 여부를 확인하지 않습니다.")
    }
}
