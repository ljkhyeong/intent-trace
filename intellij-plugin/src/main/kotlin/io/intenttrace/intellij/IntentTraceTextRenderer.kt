package io.intenttrace.intellij

internal object IntentTraceTextRenderer {
    fun render(lookup: LineLookup, found: ChangeIntentLookup): String = buildString {
        appendLine("${lookup.repositoryKey} · ${lookup.revision.take(12)}")
        appendLine("${lookup.relativePath}:${lookup.line}")
        if (found.items.isEmpty()) {
            appendLine().appendLine("이 커밋의 현재 줄에 연결된 공개 기록이 없습니다.")
            appendLine("아래 버튼으로 파일의 과거 기록이나 줄 이동·이름 변경을 찾아보세요.")
        }
        if (found.truncated) {
            appendLine().appendLine("최근 공개 기록 ${found.items.size}건만 표시합니다. 나머지는 이 파일의 과거 기록에서 확인하세요.")
        }
        append(renderRecords(found.items, lookup.revision))
    }.trimEnd()

    fun renderHistory(record: ChangeIntentRecord): String = buildString {
        appendLine("${record.repositoryKey} · 기록에 연결된 커밋: ${record.targetRevision ?: "작성자 확인 전"}")
        record.baseRevision?.let { appendLine("변경 전 커밋: $it") }
        appendLine("이 기록의 코드와 검증은 당시 스냅샷 기준입니다. 현재 편집 중인 코드의 검증이 아닙니다.")
        record.derivedFromRecordId?.let { appendLine("원본 기록: $it") }
        record.supersededBy?.let { appendLine("대체 기록: $it") }
        append(renderRecords(listOf(record)))
    }.trimEnd()

    private fun renderRecords(records: List<ChangeIntentRecord>, queryRevision: String? = null): String = buildString {
        records.forEachIndexed { index, record ->
            if (index > 0) appendLine().appendLine("────────────────────────────────────────")
            appendLine()
            appendLine(record.title)
            appendLine("상태: ${status(record.status)} · 작성자: @${record.createdBy.login}")
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

    fun renderDiagnosis(diagnosis: ConnectionDiagnosis, revision: String?): String = buildString {
        appendLine("${diagnosis.repositoryKey} · ${revision?.let { "커밋 ${it.take(12)}" } ?: "커밋 미지정"}")
        appendLine("확인 시각: ${diagnosis.checkedAt} (UTC)")
        appendLine()
        diagnosis.checks.forEach { check ->
            append("[${diagnosisStatus(check.status)}] ${checkName(check.name)}")
            if (check.message.isNotBlank()) append(" — ${check.message}")
            appendLine()
        }
        appendLine()
        append("권한과 코드 읽기만 확인합니다. 실제 게시나 테스트 실행은 하지 않습니다.")
    }

    /** 이전 커밋 조회 결과다. 여러 번 이어 읽은 결과를 합쳐 표시하며 과거 검증을 현재 검증으로 표시하지 않는다. */
    fun renderLineHistory(lookup: LineLookup, view: ChangeIntentHistory): String = buildString {
        appendLine("${lookup.repositoryKey} · ${lookup.revision.take(12)}")
        appendLine("${lookup.relativePath}:${lookup.line}")
        appendLine("마지막 조회에서 살펴본 기록 ${view.scannedRecords}건 · 누적 관련 결과 ${view.items.size}건")
        view.stopReason?.let {
            val reason = when (it) {
                "TIME_LIMIT" -> "조회 제한 시간에 도달해 중단했습니다."
                "CALL_LIMIT" -> "이번 조회의 GitHub 호출 한도에 도달했습니다."
                "CANCELLED" -> "취소 요청으로 조회를 중단했습니다."
                else -> "조회를 중단했습니다."
            }
            appendLine(if (view.resumeBlocked) "$reason 반복 조회 전에 관리자에게 조회 제한과 GitHub 지연을 확인해 달라고 요청하세요."
                else "$reason 중단 위치부터 계속 조회할 수 있습니다.")
        }
        if (view.items.isEmpty()) {
            appendLine().appendLine(if (view.complete) "조회한 기록에서 관련 결과를 찾지 못했습니다." else "표시할 결과가 없습니다. 아직 확인하지 못한 코드가 있습니다.")
        }
        view.items.forEachIndexed { index, item ->
            appendLine()
            appendLine("${index + 1}. [${matchLabel(item.match)}] ${item.record.title} · @${item.record.createdBy.login} · ${status(item.record.status)}")
            appendLine("   원본: ${item.sourcePath}:${item.sourceStartLine}-${item.sourceEndLine} · ${item.side.label} · 커밋 ${item.sourceRevision.take(12)}")
            if (item.currentStartLine != null && item.currentEndLine != null) {
                appendLine("   조회한 커밋의 줄: ${item.currentStartLine}-${item.currentEndLine}")
            }
            if (!item.verificationAppliesToQuery) appendLine("   이 기록의 테스트 결과로 조회한 커밋이 검증됐다고 볼 수 없습니다.")
        }
        if (view.failures.isNotEmpty()) {
            appendLine().appendLine("확인하지 못한 기록")
            view.failures.forEach { appendLine("- ${it.recordId}: ${failureReason(it.reason)}") }
            appendLine("같은 사유가 반복되면 웹에서 해당 기록을 다시 조회하세요.")
        }
    }.trimEnd()

    fun matchLabel(value: String): String = when (value) {
        "EXACT_REVISION" -> "커밋·줄 일치"
        "ANCESTOR_UNCHANGED_FILE" -> "과거 파일과 내용 일치"
        "ANCESTOR_RENAMED_FILE" -> "파일 이름 변경 확인"
        "ANCESTOR_UNCHANGED_LINES" -> "과거 코드 조각과 내용 일치"
        "ANCESTOR_MOVED_LINES" -> "코드 줄 이동 확인"
        else -> "관련 기록 · 코드 일치 미확인"
    }

    private fun failureReason(value: String): String = when (value) {
        "SIZE_LIMIT" -> "파일 또는 응답이 지원 크기를 초과했습니다."
        "TRUNCATED_TREE" -> "GitHub에서 전체 파일 트리를 받지 못했습니다."
        "UNSUPPORTED_OBJECT" -> "현재 지원하지 않는 Git 객체입니다."
        "REVISION_NOT_FOUND" -> "GitHub에서 커밋을 찾을 수 없습니다. 원격 저장소에 푸시했는지 확인하세요."
        else -> "코드를 확인하지 못했습니다."
    }

    private fun checkName(value: String): String = when (value) {
        "authentication" -> "GitHub 사용자 인증"
        "repository_read" -> "저장소 읽기 권한"
        "repository_write" -> "저장소 쓰기 권한"
        "pull_request_read" -> "PR 읽기"
        "pull_request_publication" -> "PR 게시 가능 여부"
        "pull_request_revision" -> "PR 현재 커밋 일치"
        "git_tree_read" -> "커밋 코드 읽기"
        "publication_credentials" -> "서버 게시 설정"
        else -> value
    }

    private fun diagnosisStatus(value: String): String = when (value) {
        "VERIFIED" -> "확인됨"
        "FAILED" -> "실패"
        "CONFIGURED_UNVERIFIED" -> "설정됨·미확인"
        "NOT_CONFIGURED" -> "미설정"
        "NOT_CHECKED" -> "확인 안 함"
        else -> value
    }

    fun status(value: String): String = when (value) {
        "DRAFT" -> "초안"
        "AUTHOR_CONFIRMED" -> "작성자 확인 · 비공개"
        "PUBLISHED" -> "팀 공개"
        "SUPERSEDED" -> "대체됨"
        "DISCARDED" -> "폐기"
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
