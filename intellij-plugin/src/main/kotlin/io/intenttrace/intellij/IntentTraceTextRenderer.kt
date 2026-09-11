package io.intenttrace.intellij

internal object IntentTraceTextRenderer {
    fun render(lookup: LineLookup, records: List<ChangeIntentRecord>): String = buildString {
        appendLine("${lookup.repositoryKey} · ${lookup.revision.take(12)}")
        appendLine("${lookup.relativePath}:${lookup.line}")
        append(renderRecords(records, lookup.revision))
    }.trimEnd()

    fun renderHistory(record: ChangeIntentRecord): String = buildString {
        appendLine("${record.repositoryKey} · 기록에 연결된 커밋: ${record.targetRevision ?: "작성자 확인 전"}")
        record.baseRevision?.let { appendLine("변경 전 커밋: $it") }
        appendLine("이 기록의 코드와 검증은 당시 스냅샷 기준입니다. 현재 편집 중인 코드의 검증이 아닙니다.")
        record.supersededBy?.let { appendLine("대체 기록: $it") }
        append(renderRecords(listOf(record)))
    }.trimEnd()

    private fun renderRecords(records: List<ChangeIntentRecord>, queryRevision: String? = null): String = buildString {
        records.forEachIndexed { index, record ->
            if (index > 0) appendLine().appendLine("────────────────────────────────────────")
            appendLine()
            appendLine(record.title)
            appendLine("상태: ${status(record.status)} · 작성자: @${record.authorLogin}")
            appendLine("기록: ${record.id}")
            appendLine()
            appendLine("요청")
            appendLine(record.requestSummary)

            appendLine().appendLine("구현 결정과 이유")
            record.decisions.forEach { decision ->
                append("- [${source(decision.source)}] ${decision.summary}")
                decision.rationale?.takeIf(String::isNotBlank)?.let { append("\n  이유: $it") }
                appendLine()
            }

            appendLine().appendLine("등록된 검증 결과")
            if (record.verifications.isEmpty()) {
                appendLine("- 등록된 검증 결과가 없습니다.")
            } else {
                record.verifications.forEach { verification ->
                    val snapshot = when {
                        queryRevision != null && !queryRevision.equals(record.targetRevision, ignoreCase = true) -> "다른 커밋의 결과"
                        verification.current -> "기록 스냅샷과 일치"
                        else -> "기록 스냅샷과 불일치"
                    }
                    appendLine("- [$snapshot, 종료 코드 ${verification.exitCode}] ${verification.command}")
                    appendLine("  ${verification.summary}")
                    val origin = when (verification.source) {
                        "LOCAL_RUNNER_REPORTED" -> "로컬 실행 도구에서 수집한 결과"
                        "CLIENT_REPORTED" -> "클라이언트가 제출한 결과"
                        else -> "미확인"
                    }
                    appendLine("  출처: $origin")
                }
                appendLine("서버는 테스트 실행 여부를 확인하지 않습니다.")
            }

            appendLine().appendLine("관련 코드")
            record.codeAnchors.forEach { anchor ->
                appendLine("- ${anchor.label}")
            }

            appendLine().appendLine("남은 질문")
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
        "PUBLISHED" -> "팀 공개"
        "SUPERSEDED" -> "대체됨"
        else -> value
    }

    private fun source(value: String): String = when (value) {
        "STATED_BY_USER" -> "사용자 요청"
        "STATED_IN_COMMIT" -> "커밋에 명시"
        "CONFIRMED_AI_SUMMARY" -> "작성자가 확인한 AI 요약"
        "INFERRED" -> "정황에서 추론"
        "UNKNOWN" -> "근거 미확인"
        else -> value
    }
}
