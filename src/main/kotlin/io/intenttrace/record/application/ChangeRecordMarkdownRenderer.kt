package io.intenttrace.record.application

import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.PurposeSource
import org.springframework.stereotype.Component

@Component
class ChangeRecordMarkdownRenderer {
    fun render(record: ChangeRecord): String = buildString {
        appendLine("# 변경 의도: ${record.title}")
        appendLine()
        appendLine("- 상태: `${record.status}`")
        appendLine("- 저장소: `${record.repositoryKey}`")
        appendLine("- 커밋: `${record.targetRevision ?: "작성자 확인 전"}`")
        appendLine("- 스냅샷: `${record.snapshotDigest}`")
        appendLine("- 작성자: `${record.createdBy}`")
        appendLine()
        appendLine("## 요청")
        appendLine()
        appendLine(record.requestSummary)
        appendLine()
        appendLine("## 판단")
        appendLine()
        record.decisions.forEach { decision ->
            append("- ${decision.summary} — ${decision.source.label}")
            decision.rationale?.takeIf(String::isNotBlank)?.let { append("\n  - 근거: $it") }
            appendLine()
        }
        appendLine()
        appendLine("## 코드 근거")
        appendLine()
        record.codeAnchors.forEach { anchor ->
            val symbol = anchor.symbolName?.let { " (`$it`)" }.orEmpty()
            appendLine("- `${anchor.relativePath}:${anchor.startLine}-${anchor.endLine}`$symbol — `${anchor.contentHash}`")
        }
        appendLine()
        appendLine("## 검증")
        appendLine()
        if (record.verifications.isEmpty()) {
            appendLine("- 실행한 검증이 없습니다.")
        } else {
            record.verifications.forEach { verification ->
                val state = when {
                    !verification.isCurrentFor(record) -> "오래된 스냅샷"
                    verification.exitCode == 0 -> "통과"
                    else -> "실패"
                }
                appendLine("- **$state** `${verification.command}` — ${verification.summary}")
                appendLine("  - 출력 해시: `${verification.outputDigest}`")
            }
        }
        appendLine()
        appendLine("## 미검증·남은 질문")
        appendLine()
        if (record.openQuestions.isEmpty()) {
            appendLine("- 현재 기록된 항목이 없습니다.")
        } else {
            record.openQuestions.forEach { appendLine("- $it") }
        }
    }

    private val PurposeSource.label: String
        get() = when (this) {
            PurposeSource.STATED_BY_USER -> "사용자가 명시함"
            PurposeSource.STATED_IN_COMMIT -> "커밋에 명시됨"
            PurposeSource.CONFIRMED_AI_SUMMARY -> "작성자가 AI 요약을 확인함"
            PurposeSource.INFERRED -> "정황에서 추론함"
            PurposeSource.UNKNOWN -> "근거를 확인하지 못함"
        }
}
