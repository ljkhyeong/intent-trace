package io.intenttrace.record.application

import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.PurposeSource
import org.springframework.stereotype.Component

@Component
class ChangeRecordMarkdownRenderer {
    fun render(record: ChangeRecord): String = buildString {
        appendLine("# 변경 의도: ${plainText(record.title)}")
        appendLine()
        appendLine("- 상태: ${inlineCode(record.status.toString())}")
        appendLine("- 저장소: ${inlineCode(record.repositoryKey)}")
        appendLine("- 커밋: ${inlineCode(record.targetRevision ?: "작성자 확인 전")}")
        appendLine("- 스냅샷: ${inlineCode(record.snapshotDigest)}")
        appendLine("- 작성자: ${inlineCode("@${record.createdBy.login}")} (${inlineCode(record.createdBy.subject)})")
        appendLine()
        appendLine("## 요청")
        appendLine()
        appendLine(plainText(record.requestSummary))
        appendLine()
        appendLine("## 판단")
        appendLine()
        record.decisions.forEach { decision ->
            append("- ${plainText(decision.summary)} — ${decision.source.label}")
            decision.rationale?.takeIf(String::isNotBlank)?.let { append("\n  - 근거: ${plainText(it)}") }
            appendLine()
        }
        appendLine()
        appendLine("## 코드 근거")
        appendLine()
        record.codeAnchors.forEach { anchor ->
            val symbol = anchor.symbolName?.takeIf(String::isNotBlank)?.let { " (${inlineCode(it)})" }.orEmpty()
            appendLine(
                "- ${inlineCode("${anchor.relativePath}:${anchor.startLine}-${anchor.endLine}")}" +
                    "$symbol — ${inlineCode(anchor.contentHash)}",
            )
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
                appendLine(
                    "- **$state** ${inlineCode(verification.command)} — ${plainText(verification.summary)}",
                )
                appendLine("  - 출력 해시: ${inlineCode(verification.outputDigest)}")
            }
        }
        appendLine()
        appendLine("## 미검증·남은 질문")
        appendLine()
        if (record.openQuestions.isEmpty()) {
            appendLine("- 현재 기록된 항목이 없습니다.")
        } else {
            record.openQuestions.forEach { appendLine("- ${plainText(it)}") }
        }
    }

    private fun plainText(value: String): String = buildString(value.length) {
        normalizedSingleLine(value).forEach { character ->
            if (character.isAsciiPunctuation()) append('\\')
            append(character)
        }
    }

    private fun inlineCode(value: String): String {
        val normalized = normalizedSingleLine(value)
        val longestBacktickRun = BACKTICK_RUN.findAll(normalized)
            .maxOfOrNull { it.value.length }
            ?: 0
        val delimiter = "`".repeat(longestBacktickRun + 1)
        val content = if (normalized.startsWith('`') || normalized.endsWith('`')) " $normalized " else normalized
        return "$delimiter$content$delimiter"
    }

    private fun normalizedSingleLine(value: String): String = value
        .trim()
        .replace(LINE_BREAK, " ")
        .replace('\t', ' ')

    private fun Char.isAsciiPunctuation(): Boolean =
        code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126

    private val PurposeSource.label: String
        get() = when (this) {
            PurposeSource.STATED_BY_USER -> "사용자가 명시함"
            PurposeSource.STATED_IN_COMMIT -> "커밋에 명시됨"
            PurposeSource.CONFIRMED_AI_SUMMARY -> "작성자가 AI 요약을 확인함"
            PurposeSource.INFERRED -> "정황에서 추론함"
            PurposeSource.UNKNOWN -> "근거를 확인하지 못함"
        }

    companion object {
        private val LINE_BREAK = Regex("\\R+")
        private val BACKTICK_RUN = Regex("`+")
    }
}
