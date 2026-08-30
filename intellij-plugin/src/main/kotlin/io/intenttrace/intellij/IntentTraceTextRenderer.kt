package io.intenttrace.intellij

internal object IntentTraceTextRenderer {
    fun render(lookup: LineLookup, records: List<ChangeIntentRecord>): String = buildString {
        appendLine("${lookup.repositoryKey} · ${lookup.revision.take(12)}")
        appendLine("${lookup.relativePath}:${lookup.line}")
        append(renderRecords(records))
    }.trimEnd()

    fun renderHistory(record: ChangeIntentRecord): String = buildString {
        appendLine("${record.repositoryKey} · 기록에 연결된 커밋: ${record.targetRevision ?: "아직 확인하지 않음"}")
        appendLine("이 기록의 코드와 검증은 당시 스냅샷 기준입니다. 현재 편집 중인 코드의 검증이 아닙니다.")
        record.supersededBy?.let { appendLine("대체 기록: $it") }
        append(renderRecords(listOf(record)))
    }.trimEnd()

    private fun renderRecords(records: List<ChangeIntentRecord>): String = buildString {
        records.forEachIndexed { index, record ->
            if (index > 0) appendLine().appendLine("────────────────────────────────────────")
            appendLine()
            appendLine(record.title)
            appendLine("상태: ${status(record.status)} · 작성자: @${record.authorLogin}")
            appendLine("기록: ${record.id}")
            appendLine()
            appendLine("요청")
            appendLine(record.requestSummary)

            appendLine().appendLine("판단")
            record.decisions.forEach { decision ->
                append("- [${source(decision.source)}] ${decision.summary}")
                decision.rationale?.takeIf(String::isNotBlank)?.let { append("\n  이유: $it") }
                appendLine()
            }

            appendLine().appendLine("검증")
            if (record.verifications.isEmpty()) {
                appendLine("- 기록된 검증 없음")
            } else {
                record.verifications.forEach { verification ->
                    val snapshot = if (verification.current) "기록 스냅샷과 일치" else "기록 스냅샷과 불일치"
                    appendLine("- [$snapshot, exit ${verification.exitCode}] ${verification.command}")
                    appendLine("  ${verification.summary}")
                }
            }

            appendLine().appendLine("코드 근거")
            record.codeAnchors.forEach { anchor ->
                appendLine("- ${anchor.relativePath}:${anchor.startLine}-${anchor.endLine}")
            }

            appendLine().appendLine("미확인 항목")
            if (record.openQuestions.isEmpty()) {
                appendLine("- 없음")
            } else {
                record.openQuestions.forEach { appendLine("- $it") }
            }
        }
    }.trimEnd()

    fun status(value: String): String = when (value) {
        "DRAFT" -> "초안"
        "AUTHOR_CONFIRMED" -> "작성자 확인 · 비공개"
        "PUBLISHED" -> "공개"
        "SUPERSEDED" -> "대체됨"
        else -> value
    }

    private fun source(value: String): String = when (value) {
        "STATED_BY_USER" -> "사용자 요청"
        "STATED_IN_COMMIT" -> "커밋"
        "CONFIRMED_AI_SUMMARY" -> "작성자 확인 AI 요약"
        "INFERRED" -> "추론"
        "UNKNOWN" -> "미확인"
        else -> value
    }
}
