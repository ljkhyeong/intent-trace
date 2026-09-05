package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.connection.application.ConnectionDiagnosis
import io.intenttrace.connection.application.DiagnosticStatus
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.publication.application.PublicationAttemptStatus
import io.intenttrace.publication.application.PublicationOperation
import io.intenttrace.publication.application.PullRequestOverview
import io.intenttrace.record.application.ChangeRecordComparison
import io.intenttrace.record.application.ComparisonField
import io.intenttrace.record.application.RecordComparisonSide
import io.intenttrace.record.application.ItemChange
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.VerificationSource

internal fun RecordBrowserPage.pullRequests(actor: ActorIdentity, repository: String?, number: Int?, result: PullRequestOverview?): String =
    layout("PR 변경 기록", actor, buildString {
        append("<header class=\"page-heading\"><h1>PR 변경 기록</h1><p>현재 PR 커밋과 게시 기록을 함께 확인하세요.</p></header>")
        append("""<form action="/records/pull-requests" class="search-form" method="get">
            <label>저장소<input name="repositoryKey" value="${html(repository.orEmpty())}" placeholder="owner/repository" required></label>
            <label>PR 번호<input name="pullNumber" type="number" min="1" value="${number ?: ""}" required></label><button>조회</button></form>""")
        if (result == null) append("<p class=\"empty\">저장소와 PR 번호를 입력해 주세요.</p>") else {
            append("<p>PR #${result.pullNumber} · ${stamp(result.checkedAt)}</p><p class=\"hash\">현재 커밋 ${html(result.headRevision)}</p>")
            if (result.fork) append("<aside class=\"notice\">Fork PR에는 Check Run을 게시할 수 없습니다.</aside>")
            if (result.items.isEmpty()) append("<p class=\"empty\">이 PR에 게시했거나 게시를 시도한 기록이 없습니다.</p>")
            append("<ul class=\"records\">")
            result.items.forEach { item ->
                val attempt = item.latestAttempt
                val latest = when (attempt?.status) {
                    PublicationAttemptStatus.IN_PROGRESS -> "게시 요청 처리 중"
                    PublicationAttemptStatus.RESULT_UNKNOWN -> "게시 결과 미확인 · 기존 게시 요청을 다시 실행해 확인해 주세요"
                    PublicationAttemptStatus.FAILED -> "최근 게시 요청 실패"
                    PublicationAttemptStatus.SUCCEEDED -> if (attempt.operation == PublicationOperation.SUPERSESSION_NOTICE) "대체 안내 완료" else "게시 완료"
                    null -> if (item.publication != null) "게시 완료" else "게시 결과 없음"
                }
                append("<li><div class=\"record-summary\"><span class=\"status\">${if (item.matchesCurrentHead) "현재 커밋과 일치" else "이전 커밋의 기록"}</span>")
                append("<h2><a href=\"/records/${item.record.id}\">${html(item.record.title)}</a></h2><p>${html(item.record.requestSummary)}</p><p>$latest</p>")
                if (item.publication != null) append("<p class=\"muted\">마지막으로 확인한 게시: ${stamp(item.publication.publishedAt)}</p>")
                append("</div></li>")
            }
            append("</ul>")
            result.nextCursor?.let { append("<nav class=\"pagination\"><a class=\"button secondary\" href=\"${html(url("/records/pull-requests", "repositoryKey" to repository, "pullNumber" to number.toString(), "cursor" to it))}\">다음 기록</a></nav>") }
        }
    })

internal fun RecordBrowserPage.connection(actor: ActorIdentity, repository: String?, revision: String?, number: Int?, result: ConnectionDiagnosis?): String =
    layout("연결 진단", actor, buildString {
        append("<header class=\"page-heading\"><h1>연결 진단</h1><p>저장소 권한과 PR·코드 읽기를 확인하세요.</p></header>")
        append("""<form action="/records/connection" class="search-form" method="get">
            <label>저장소<input name="repositoryKey" value="${html(repository.orEmpty())}" placeholder="owner/repository" required></label>
            <label>PR 번호 · 선택<input name="pullNumber" type="number" min="1" value="${number ?: ""}"></label>
            <label>전체 커밋 · 선택<input name="revision" value="${html(revision.orEmpty())}" placeholder="PR을 입력하면 현재 커밋 사용" maxlength="64"></label><button>진단</button></form>""")
        if (result == null) append("<p class=\"empty\">저장소를 입력하면 연결 상태를 확인합니다.</p>") else {
            append("<p>${stamp(result.checkedAt)}</p><ul class=\"records\">")
            val names = mapOf("authentication" to "사용자 인증", "repository_read" to "저장소 읽기", "repository_write" to "저장소 쓰기",
                "pull_request_read" to "PR 읽기", "pull_request_publication" to "PR 게시 대상", "git_tree_read" to "커밋 트리 읽기", "publication_credentials" to "게시 자격 증명 설정")
            result.checks.forEach { check ->
                val status = when (check.status) {
                    DiagnosticStatus.VERIFIED -> "확인 완료"; DiagnosticStatus.FAILED -> "확인 실패"
                    DiagnosticStatus.CONFIGURED_UNVERIFIED -> "설정 있음 · 원격 확인 전"; DiagnosticStatus.NOT_CONFIGURED -> "설정 필요"; DiagnosticStatus.NOT_CHECKED -> "확인하지 않음"
                }
                append("<li><div><span class=\"status\">$status</span><h2>${html(names[check.name] ?: check.name)}</h2><p>${html(check.message)}</p></div></li>")
            }
            append("</ul><p class=\"notice\">게시 키·설치·실제 발급 권한은 관리자가 연결된 Agent에서 ‘게시 자격 증명 사전 점검’을 요청해 확인할 수 있습니다.</p>")
        }
    })

internal fun RecordBrowserPage.comparison(actor: ActorIdentity, result: ChangeRecordComparison, changesOnly: Boolean = false): String =
    layout("원본과 후속 기록 비교", actor, buildString {
        append("<header class=\"page-heading\"><h1>원본과 후속 기록 비교</h1><p>바뀐 판단과 근거를 확인한 뒤 후속 기록을 검토하세요.</p></header>")
        if (result.successor.content.verifications.isEmpty()) append("<aside class=\"notice\">후속 기록에 제출된 검증이 없습니다. 원본의 검증은 후속 기록의 검증으로 이어지지 않습니다.</aside>")
        append("<div class=\"comparison-columns comparison-heading\"><p><a href=\"/records/${result.original.id}\">원본 기록</a> · 버전 ${result.original.version}</p><p><a href=\"/records/${result.successor.id}\">후속 기록</a> · 버전 ${result.successor.version}</p></div>")
        append("<nav class=\"comparison-filter\"><a class=\"button secondary\" href=\"/records/${result.successor.id}/comparison?changesOnly=${!changesOnly}\">${if (changesOnly) "같은 항목도 함께 보기" else "변경된 항목만 보기"}</a><p>${if (changesOnly) "변경된 항목만 표시 중" else "전체 항목 표시 중"}</p></nav>")
        val labels = mapOf(ComparisonField.TITLE to "제목", ComparisonField.REQUEST to "요청", ComparisonField.DECISIONS to "판단과 출처",
            ComparisonField.CODE_ANCHORS to "코드 근거", ComparisonField.VERIFICATIONS to "검증", ComparisonField.OPEN_QUESTIONS to "남은 질문",
            ComparisonField.BASE_REVISION to "변경 전 커밋", ComparisonField.TARGET_REVISION to "변경 후 커밋", ComparisonField.SNAPSHOT to "스냅샷")
        labels.filterKeys { !changesOnly || it in result.changedFields }.forEach { (field, label) ->
            val details = result.details.filter { it.field == field }
            val before = comparisonItems(field, result.original)
            val after = comparisonItems(field, result.successor)
            append("<section class=\"comparison-section\"><h2>$label <span class=\"status\">${if (field in result.changedFields) "변경됨" else "같음"}</span></h2>")
            details.forEach { detail ->
                val name = when (detail.change) {
                    ItemChange.ADDED -> "추가"; ItemChange.REMOVED -> "삭제"; ItemChange.MODIFIED -> "내용 변경"
                    ItemChange.MOVED -> "순서 변경"; ItemChange.AMBIGUOUS -> "중복 항목 · 대응 불명확"
                }
                val propertyLabels = mapOf("source" to "출처", "rationale" to "판단 근거", "summary" to "요약", "contentHash" to "줄 해시",
                    "symbolName" to "심볼", "relatedPath" to "연결 경로", "exitCode" to "종료 코드", "snapshotDigest" to "스냅샷", "outputDigest" to "출력 해시")
                append("<div class=\"comparison-detail\"><h3>$name${detail.changedProperties.takeIf { it.isNotEmpty() }?.joinToString(", ", " · ") { propertyLabels[it] ?: it }.orEmpty()}</h3>")
                if (detail.change == ItemChange.AMBIGUOUS) append("<p>중복 항목을 임의로 합치지 않았습니다. 아래 원본·후속 전체 내용을 확인하세요.</p>")
                else {
                    append("<p>${detail.originalIndex?.let { "원본 ${it + 1}번" }.orEmpty()}${if (detail.originalIndex != null && detail.successorIndex != null) " → " else ""}${detail.successorIndex?.let { "후속 ${it + 1}번" }.orEmpty()}${if (detail.moved && detail.change != ItemChange.MOVED) " · 순서도 변경" else ""}</p>")
                    val left = detail.originalIndex?.let { before[it] }.orEmpty()
                    val right = detail.successorIndex?.let { after[it] }.orEmpty()
                    append("<div class=\"comparison-columns\"><div><h4>원본</h4><p class=\"prose\">${highlightChangedLines(left, right, "del")}</p></div><div><h4>후속</h4><p class=\"prose\">${highlightChangedLines(right, left, "ins")}</p></div></div>")
                }
                append("</div>")
            }
            if (details.isNotEmpty()) append("<details><summary>이 항목의 전체 원본·후속 내용</summary>")
            append("<div class=\"comparison-columns\">")
            listOf("원본" to result.original, "후속" to result.successor).forEach { (side, value) ->
                val other = if (value === result.original) result.successor else result.original
                val text = comparisonText(field, value)
                val displayed = if (details.isEmpty() && field in result.changedFields) highlightChangedLines(text, comparisonText(field, other), if (side == "원본") "del" else "ins") else html(text)
                append("<div class=\"comparison-value\"><h3>$side</h3><p class=\"prose\">$displayed</p></div>")
            }
            append("</div>")
            if (details.isNotEmpty()) append("</details>")
            append("</section>")
        }
    })

private fun comparisonText(field: ComparisonField, side: RecordComparisonSide): String = with(side.content) {
    when (field) {
        ComparisonField.TITLE -> title
        ComparisonField.REQUEST -> requestSummary
        ComparisonField.DECISIONS, ComparisonField.CODE_ANCHORS, ComparisonField.VERIFICATIONS, ComparisonField.OPEN_QUESTIONS -> comparisonItems(field, side).joinToString("\n\n")
        ComparisonField.BASE_REVISION -> baseRevision.orEmpty()
        ComparisonField.TARGET_REVISION -> side.targetRevision.orEmpty()
        ComparisonField.SNAPSHOT -> snapshotDigest
    }.ifEmpty { "등록된 내용 없음" }
}

private fun comparisonItems(field: ComparisonField, side: RecordComparisonSide): List<String> = with(side.content) {
    when (field) {
        ComparisonField.DECISIONS -> decisions.map { "${it.source.label}\n${it.summary}\n${it.rationale.orEmpty()}" }
        ComparisonField.CODE_ANCHORS -> codeAnchors.map { "${if (it.side == CodeSide.BASE) "변경 전" else "변경 후"} ${it.relativePath}:${it.startLine}–${it.endLine}\n${it.symbolName.orEmpty()}\n줄 해시 ${it.contentHash}${it.relatedPath?.let { path -> "\n연결 경로 $path" }.orEmpty()}" }
        ComparisonField.VERIFICATIONS -> verifications.map { "${it.command}\n종료 코드 ${it.exitCode} · ${if (it.source == VerificationSource.LOCAL_RUNNER_REPORTED) "로컬 실행 도구 수집" else "클라이언트 제출"}\n${it.summary}\n${it.startedAt} ~ ${it.finishedAt}\n스냅샷 ${it.snapshotDigest}\n출력 해시 ${it.outputDigest}" }
        ComparisonField.OPEN_QUESTIONS -> openQuestions
        else -> emptyList()
    }
}

private fun highlightChangedLines(value: String, other: String, tag: String): String {
    if (value.isEmpty()) return "등록된 내용 없음"
    if (value == other) return html(value)
    val lines = value.split('\n'); val otherLines = other.split('\n')
    val prefix = lines.zip(otherLines).takeWhile { it.first == it.second }.size
    val suffix = lines.drop(prefix).asReversed().zip(otherLines.drop(prefix).asReversed()).takeWhile { it.first == it.second }.size
    return lines.mapIndexed { index, line -> if (index >= prefix && index < lines.size - suffix) "<$tag>${html(line)}</$tag>" else html(line) }.joinToString("\n")
}
