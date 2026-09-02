package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import io.intenttrace.record.domain.VerificationRun
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ChangeRecordMarkdownRendererTest {
    private val renderer = ChangeRecordMarkdownRenderer()

    @Test
    fun `외부 문장은 Markdown 구조를 만들지 않고 백틱이 있는 코드는 그대로 표시한다`() {
        val record = ChangeRecord(
            id = UUID.fromString("8c766289-5c2c-4b1f-90e6-376058868c42"),
            requestId = "markdown-test",
            repositoryKey = "acme/intent-trace",
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
            codeAnchors = listOf(CodeAnchor("src/Strange`Name.kt", "`symbol`", 1, 2, "c".repeat(64))),
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

        val markdown = renderer.render(record)

        assertContains(markdown, "# 변경 의도: \\# 가짜 제목")
        assertContains(markdown, "\\[가짜 링크\\]\\(https\\:\\/\\/example\\.test\\) \\#\\# 주입된 제목")
        assertContains(markdown, "- \\- 새 목록 — 사용자가 명시함")
        assertContains(markdown, "근거: \\*\\*강조된 근거\\*\\*")
        assertContains(markdown, "``src/Strange`Name.kt:1-2``")
        assertContains(markdown, "(`` `symbol` ``)")
        assertContains(markdown, "``echo `pwd` ./gradlew test``")
        assertContains(markdown, "— \\> 성공처럼 보이는 인용")
        assertContains(markdown, "- \\# 확인할 질문")
        assertFalse(markdown.contains("\n## 주입된 제목"))
    }
}
