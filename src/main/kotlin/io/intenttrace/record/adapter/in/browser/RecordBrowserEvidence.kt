package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.AnchorCheckStatus
import io.intenttrace.record.application.ChangeIntentHistory
import io.intenttrace.record.application.EvidenceUnavailableReason
import io.intenttrace.record.application.HistoryStopReason
import io.intenttrace.record.application.IntentMatch
import io.intenttrace.record.application.RecordEvidenceCheck
import io.intenttrace.record.domain.CodeSide

internal fun RecordBrowserPage.history(actor: ActorIdentity, repository: String?, revision: String?, path: String?, line: Int?, result: ChangeIntentHistory?): String =
    layout("파일·줄로 기록 찾기", actor, buildString {
        append("<header class=\"page-heading\"><h1>파일·줄로 기록 찾기</h1><p>이 코드에 연결된 요청과 이전 커밋의 판단을 찾아보세요.</p></header>")
        append("""<form action="/records/history" method="get" class="search-form">
            <label>저장소<input name="repositoryKey" value="${html(repository.orEmpty())}" placeholder="owner/repository" required></label>
            <label>전체 커밋<input name="revision" value="${html(revision.orEmpty())}" minlength="40" maxlength="64" required></label>
            <label>파일 경로<input name="path" value="${html(path.orEmpty())}" placeholder="src/App.kt" required></label>
            <label>줄 번호<input name="line" type="number" min="1" value="${line ?: ""}" required></label><button>기록 찾기</button></form>""")
        if (result == null) append("<p class=\"empty\">조회할 코드의 저장소·전체 커밋·파일·줄을 입력해 주세요.</p>") else {
            fun query(extra: String, value: String) = html(url("/records/history", "repositoryKey" to repository,
                "revision" to revision, "path" to path, "line" to line.toString(), extra to value))
            append("<p>후보 ${result.scannedRecords}건 살펴봄 · 일치 또는 관련 결과 ${result.items.size}건</p>")
            if (result.items.isEmpty()) append("<p class=\"empty\">이번 후보에서 연결된 기록을 찾지 못했습니다.</p>")
            if (result.failures.isNotEmpty()) append("<aside class=\"notice\">확인하지 못한 후보가 있습니다. 아래 사유와 재조회를 확인해 주세요.</aside>")
            result.stopReason?.let {
                val reason = when (it) {
                    HistoryStopReason.TIME_LIMIT -> "조회 시간이 길어져 이번 확인을 중단했습니다."
                    HistoryStopReason.CALL_LIMIT -> "한 번에 확인할 GitHub 호출 수에 도달했습니다."
                    HistoryStopReason.CANCELLED -> "조회 취소가 전달되어 추가 확인을 중단했습니다."
                }
                append("<aside class=\"notice\">$reason 아래에서 중단한 근거부터 이어서 확인할 수 있습니다.</aside>")
            }
            append("<ul class=\"records\">")
            val labels = mapOf(IntentMatch.EXACT_REVISION to "커밋·줄 일치", IntentMatch.ANCESTOR_UNCHANGED_FILE to "과거의 동일 파일",
                IntentMatch.ANCESTOR_RENAMED_FILE to "파일 이름 변경 확인", IntentMatch.ANCESTOR_UNCHANGED_LINES to "과거의 동일 코드 조각",
                IntentMatch.ANCESTOR_MOVED_LINES to "코드 줄 이동 확인", IntentMatch.RELATED_UNVERIFIED to "관련 후보 · 현재 코드 일치 미확인")
            result.items.forEach { item ->
                append("<li><div><span class=\"status\">${labels.getValue(item.match)}</span><h2><a href=\"/records/${item.record.id}\">${html(item.record.title)}</a></h2><p>${html(item.record.requestSummary)}</p>")
                append("<p>원본: ${html(item.sourcePath)}:${item.sourceStartLine}–${item.sourceEndLine} · ${if (item.side == CodeSide.BASE) "변경 전" else "변경 후"}</p><p class=\"hash\">${html(item.sourceRevision)}</p>")
                if (item.currentStartLine != null) append("<p>조회한 파일의 줄: ${item.currentStartLine}–${item.currentEndLine}</p>")
                if (!item.verificationAppliesToQuery) append("<p class=\"muted\">이 기록의 테스트 결과는 조회한 커밋의 검증으로 적용하지 않습니다.</p>")
                append("</div></li>")
            }
            append("</ul>")
            if (result.failures.isNotEmpty()) {
                append("<section><h2>확인하지 못한 후보</h2><ul class=\"records\">")
                result.failures.forEach { failure ->
                    val reason = failure.reason.message
                    append("<li><div><a href=\"/records/${failure.recordId}\">기록 읽기</a><p>$reason</p><a class=\"button secondary\" href=\"${query("retryRecordId", failure.recordId.toString())}\">이 후보 다시 확인</a></div></li>")
                }
                append("</ul><p class=\"muted\">크기와 객체 형식이 그대로라면 재조회해도 같은 사유가 나올 수 있습니다.</p></section>")
            }
            result.nextCursor?.let { append("<nav class=\"pagination\"><a class=\"button\" href=\"${query("cursor", it)}\">${if (result.stopReason != null) "중단한 근거부터 계속" else "다음 후보 확인"}</a><p>결과가 없어도 아직 확인할 후보가 남아 있습니다.</p></nav>") }
        }
    })

internal fun RecordBrowserPage.evidence(actor: ActorIdentity, result: RecordEvidenceCheck): String =
    layout("코드 근거 확인", actor, buildString {
        append("<a class=\"back-link\" href=\"/records/${result.recordId}\">기록으로 돌아가기</a><header class=\"page-heading\"><h1>코드 근거 확인</h1></header>")
        append("<aside class=\"notice\">${if (result.codeVerified) "스냅샷과 모든 코드 근거가 일치합니다." else "스냅샷 또는 코드 근거가 일치하지 않습니다."} 서버가 테스트 실행 자체를 확인한 결과는 아닙니다.</aside>")
        append("<dl class=\"evidence-facts\"><dt>기록 버전</dt><dd>${result.recordVersion}</dd><dt>확인 시각</dt><dd>${stamp(result.checkedAt)}</dd><dt>전체 커밋</dt><dd class=\"hash\">${html(result.targetRevision)}</dd><dt>기록 스냅샷</dt><dd class=\"hash\">${html(result.snapshotDigest)}</dd><dt>스냅샷 비교</dt><dd>${if (result.snapshotMatches) "일치" else "불일치"}</dd></dl><ul class=\"records\">")
        result.anchors.forEach { anchor ->
            val status = when (anchor.status) {
                AnchorCheckStatus.MATCHED -> "줄 해시 일치"; AnchorCheckStatus.HASH_MISMATCH -> "줄 해시 불일치"
                AnchorCheckStatus.FILE_MISSING -> "파일 없음"; AnchorCheckStatus.LINE_RANGE_MISSING -> "줄 범위 없음"
                AnchorCheckStatus.UNSUPPORTED_OBJECT -> "지원하지 않는 객체"
            }
            append("<li><div><span class=\"status\">$status</span><h2>${html(anchor.path)}:${anchor.startLine}–${anchor.endLine}</h2><p>${if (anchor.side == CodeSide.BASE) "변경 전" else "변경 후"}</p><p class=\"hash\">${html(anchor.revision)}</p></div></li>")
        }
        append("</ul>")
    })

internal val EvidenceUnavailableReason.message: String get() = when (this) {
    EvidenceUnavailableReason.SIZE_LIMIT -> "파일 또는 응답이 지원 크기를 초과했습니다."
    EvidenceUnavailableReason.TRUNCATED_TREE -> "GitHub에서 전체 파일 트리를 받지 못했습니다."
    EvidenceUnavailableReason.UNSUPPORTED_OBJECT -> "현재 지원하지 않는 Git 객체입니다."
}

internal fun RecordBrowserPage.evidenceUnavailable(actor: ActorIdentity, recordId: java.util.UUID, reason: EvidenceUnavailableReason): String =
    layout("코드 근거 확인 불가", actor, """
        <a class="back-link" href="/records/$recordId">기록으로 돌아가기</a>
        <section class="empty"><h1>코드 근거를 확인하지 못했습니다</h1><p>${reason.message}</p>
        <p>크기와 객체 형식이 그대로라면 다시 확인해도 같은 사유가 나올 수 있습니다.</p>
        <p class="muted">코드 일치 여부는 미확인입니다. 코드 불일치나 테스트 실패를 뜻하지 않습니다.</p></section>
    """)
