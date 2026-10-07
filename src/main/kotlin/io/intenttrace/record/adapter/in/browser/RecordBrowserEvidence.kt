package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.record.application.AnchorCheckStatus
import io.intenttrace.record.application.ChangeIntentHistory
import io.intenttrace.record.application.EvidenceUnavailableReason
import io.intenttrace.record.application.HistoryStopReason
import io.intenttrace.record.application.IntentMatch
import io.intenttrace.record.application.RecordEvidenceCheck
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.ModelAndView
import java.util.UUID

internal fun RecordBrowserPage.history(repository: String?, revision: String?, path: String?, line: Int?,
    result: ChangeIntentHistory?, searchUrl: String?): ModelAndView {
    fun query(extra: String, value: String) = url("/records/history", "repositoryKey" to repository,
        "revision" to revision, "path" to path, "line" to line.toString(), extra to value)
    return view("history", "파일·줄로 기록 찾기", HistoryView(repository.orEmpty(), revision.orEmpty(), path.orEmpty(),
        line?.toString().orEmpty(), result?.let {
            val stopped = it.stopReason?.let { reason ->
                val guidance = if (it.resumeBlocked) {
                    "코드 확인을 완료하지 못했습니다. 반복 조회 전에 관리자에게 조회 제한과 GitHub 지연을 확인해 달라고 요청하세요."
                } else "중단 위치부터 계속 조회할 수 있습니다."
                "${reason.message} $guidance"
            }
            HistoryResultView(it.scannedRecords, it.items.size,
                if (it.items.isNotEmpty()) null else if (it.complete) "조회한 기록에서 관련 결과를 찾지 못했습니다." else "표시할 결과가 없습니다. 아직 확인하지 못한 코드가 있습니다.",
                it.failures.isNotEmpty(), stopped, it.items.map { item ->
                    val current = item.currentStartLine?.let { start -> item.currentEndLine?.let { end -> start to end } }
                    HistoryItemView(item.match.label, Link(recordUrl(item.record.id, searchUrl), item.record.title),
                        item.record.requestSummary, "${item.sourcePath}:${item.sourceStartLine}–${item.sourceEndLine} · ${item.side.label}",
                        item.sourceRevision, item.currentStartLine?.let { start -> "$start–${item.currentEndLine}" },
                        codeUrl(item.record.repositoryKey, item.sourceRevision, item.sourcePath, item.sourceStartLine, item.sourceEndLine),
                        current?.let { (start, end) -> codeUrl(item.record.repositoryKey, it.queryRevision, it.path, start, end) },
                        item.verificationAppliesToQuery)
                }, it.failures.map { failure ->
                    HistoryFailureView(recordUrl(failure.recordId, searchUrl), failure.reason.message, query("retryRecordId", failure.recordId.toString()))
                }, it.nextCursor?.let { cursor ->
                    Link(query("cursor", cursor), when {
                        it.resumeBlocked -> "원인 확인 후 다시 조회"
                        it.stopReason != null -> "중단 위치부터 계속 조회"
                        else -> "다음 기록 조회"
                    })
                })
        }))
}

internal fun RecordBrowserPage.evidence(result: RecordEvidenceCheck, searchUrl: String?): ModelAndView =
    view("evidence", "GitHub 코드와 비교", EvidenceView(recordUrl(result.recordId, searchUrl),
        if (result.codeVerified) "스냅샷 해시와 모든 관련 코드가 일치합니다." else "스냅샷 해시 또는 관련 코드가 일치하지 않습니다.",
        result.recordVersion, time(result.checkedAt), result.targetRevision, result.snapshotDigest,
        if (result.snapshotMatches) "일치" else "불일치", result.anchors.map {
            EvidenceAnchorView(it.status.label, "${it.path}:${it.startLine}–${it.endLine}", it.side.label, it.revision)
        }))

internal fun RecordBrowserPage.evidenceUnavailable(recordId: UUID, reason: EvidenceUnavailableReason, searchUrl: String?): ModelAndView =
    view("evidence-unavailable", "코드 확인 불가", EvidenceUnavailableView(recordUrl(recordId, searchUrl), reason.message), HttpStatus.UNPROCESSABLE_CONTENT)

private val HistoryStopReason.message: String get() = when (this) {
    HistoryStopReason.TIME_LIMIT -> "조회 제한 시간에 도달해 중단했습니다."
    HistoryStopReason.CALL_LIMIT -> "이번 조회의 GitHub 호출 한도에 도달했습니다."
    HistoryStopReason.CANCELLED -> "취소 요청으로 조회를 중단했습니다."
}

private val IntentMatch.label: String get() = when (this) {
    IntentMatch.EXACT_REVISION -> "커밋·줄 일치"; IntentMatch.ANCESTOR_UNCHANGED_FILE -> "과거 파일과 내용 일치"
    IntentMatch.ANCESTOR_RENAMED_FILE -> "파일 이름 변경 확인"; IntentMatch.ANCESTOR_UNCHANGED_LINES -> "과거 코드 조각과 내용 일치"
    IntentMatch.ANCESTOR_MOVED_LINES -> "코드 줄 이동 확인"; IntentMatch.RELATED_UNVERIFIED -> "관련 기록 · 코드 일치 미확인"
}

private val AnchorCheckStatus.label: String get() = when (this) {
    AnchorCheckStatus.MATCHED -> "줄 해시 일치"; AnchorCheckStatus.HASH_MISMATCH -> "줄 해시 불일치"
    AnchorCheckStatus.FILE_MISSING -> "파일 없음"; AnchorCheckStatus.LINE_RANGE_MISSING -> "줄 범위 없음"
    AnchorCheckStatus.UNSUPPORTED_OBJECT -> "지원하지 않는 Git 객체"
}

data class HistoryView(val repository: String, val revision: String, val path: String, val line: String, val result: HistoryResultView?)

/** [emptyMessage]는 결과가 없을 때만, [stopNotice]는 조회가 중단됐을 때만 값이 있다. */
data class HistoryResultView(val scanned: Int, val found: Int, val emptyMessage: String?, val failed: Boolean,
    val stopNotice: String?, val items: List<HistoryItemView>, val failures: List<HistoryFailureView>, val next: Link?)

data class HistoryItemView(val match: String, val record: Link, val summary: String, val source: String,
    val sourceRevision: String, val currentLines: String?, val sourceCodeUrl: String, val currentCodeUrl: String?,
    val verificationApplies: Boolean)

data class HistoryFailureView(val recordUrl: String, val reason: String, val retryUrl: String)

data class EvidenceView(val backUrl: String, val notice: String, val version: Long, val checkedAt: TimeView,
    val targetRevision: String, val snapshotDigest: String, val snapshotMatch: String, val anchors: List<EvidenceAnchorView>)

data class EvidenceAnchorView(val status: String, val location: String, val side: String, val revision: String)

data class EvidenceUnavailableView(val backUrl: String, val reason: String)
