package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.connection.application.ConnectionDiagnosis
import io.intenttrace.connection.application.DiagnosticStatus
import io.intenttrace.publication.application.PublicationAttempt
import io.intenttrace.publication.application.PublicationAttemptStatus
import io.intenttrace.publication.application.PublicationOperation
import io.intenttrace.publication.application.PullRequestOverview
import io.intenttrace.publication.application.RecordPublications
import io.intenttrace.record.application.ChangeRecordComparison
import io.intenttrace.record.application.ComparisonField
import io.intenttrace.record.application.ItemChange
import io.intenttrace.record.application.RecordComparisonSide
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.VerificationSource
import org.springframework.web.servlet.ModelAndView

internal fun publicationLabel(attempt: PublicationAttempt?, published: Boolean): String = when (attempt?.status) {
    PublicationAttemptStatus.IN_PROGRESS -> "게시 요청 처리 중"
    PublicationAttemptStatus.RESULT_UNKNOWN -> "게시 결과 미확인 · 기존 게시 요청을 다시 실행해 확인해 주세요"
    PublicationAttemptStatus.FAILED -> "최근 게시 요청 실패"
    PublicationAttemptStatus.SUCCEEDED -> if (attempt.operation == PublicationOperation.SUPERSESSION_NOTICE) "대체 안내 완료" else "게시 완료"
    null -> if (published) "게시 완료" else "게시 결과 없음"
}

/** 기록 상세의 GitHub 게시 목록이다. 대체 안내 반영은 작성자가 REST·MCP로 요청한다. */
internal fun publicationFacts(result: RecordPublications) = PublicationsView(result.items.map { item ->
    PublicationView(Link(url("/records/pull-requests", "repositoryKey" to item.repositoryKey, "pullNumber" to item.pullNumber.toString()),
        if (item.repositoryKey == result.record.repositoryKey) "PR #${item.pullNumber}" else "${item.repositoryKey}#${item.pullNumber}"),
        publicationLabel(item.latestAttempt, item.publication != null), item.publication?.checkRunUrl, item.supersessionNoticeNeeded)
}, result.truncated, result.items.any { it.supersessionNoticeNeeded })

internal fun RecordBrowserPage.pullRequests(repository: String?, number: Int?, result: PullRequestOverview?,
    searchUrl: String): ModelAndView = view("pull-requests", "PR 변경 기록", PullRequestsView(repository.orEmpty(),
    number?.toString().orEmpty(), result?.let {
        PullRequestResultView(it.pullNumber, time(it.checkedAt), it.headRevision,
            url("/records/github", "repositoryKey" to it.repositoryKey, "number" to it.pullNumber.toString()),
            url("/records/github", "repositoryKey" to it.repositoryKey, "revision" to it.headRevision), it.fork,
            it.items.map { item ->
                PullRequestItemView(if (item.matchesCurrentHead) "현재 커밋과 일치" else "PR 최신 커밋과 다름",
                    Link(recordUrl(item.record.id, searchUrl), item.record.title), item.record.requestSummary,
                    publicationLabel(item.latestAttempt, item.publication != null), item.publication?.publishedAt?.let(::time))
            },
            it.nextCursor?.let { cursor -> url("/records/pull-requests", "repositoryKey" to repository, "pullNumber" to number.toString(), "cursor" to cursor) })
    }))

internal fun RecordBrowserPage.connection(repository: String?, revision: String?, number: Int?, result: ConnectionDiagnosis?): ModelAndView =
    view("connection", "연결 진단", ConnectionView(repository.orEmpty(), number?.toString().orEmpty(), revision.orEmpty(),
        result?.let {
            ConnectionResultView(time(it.checkedAt), it.checks.map { check ->
                ConnectionCheckView(check.status.label, checkNames[check.name] ?: check.name, check.message)
            })
        }))

private val checkNames = mapOf("authentication" to "사용자 인증", "repository_read" to "저장소 읽기", "repository_write" to "저장소 쓰기",
    "pull_request_read" to "PR 읽기", "pull_request_publication" to "PR 게시 대상", "pull_request_revision" to "PR 커밋 일치",
    "git_tree_read" to "커밋 트리 읽기", "publication_credentials" to "GitHub 게시 인증 설정")

private val DiagnosticStatus.label: String get() = when (this) {
    DiagnosticStatus.VERIFIED -> "확인 완료"; DiagnosticStatus.FAILED -> "확인 실패"
    DiagnosticStatus.CONFIGURED_UNVERIFIED -> "설정됨 · 유효성 미확인"; DiagnosticStatus.NOT_CONFIGURED -> "설정 필요"; DiagnosticStatus.NOT_CHECKED -> "확인하지 않음"
}

internal fun RecordBrowserPage.comparison(result: ChangeRecordComparison, changesOnly: Boolean, searchUrl: String?): ModelAndView {
    val sections = ComparisonField.entries.filter { !changesOnly || it in result.changedFields }.map { field ->
        val changed = field in result.changedFields
        val details = result.details.filter { it.field == field }
        val before = comparisonItems(field, result.original)
        val after = comparisonItems(field, result.successor)
        val originalText = before.joinToString("\n\n").ifEmpty { EMPTY }
        val successorText = after.joinToString("\n\n").ifEmpty { EMPTY }
        val highlighted = details.isEmpty() && changed
        ComparisonSectionView(field.label, if (changed) "변경됨" else "같음", details.map { detail ->
            val properties = detail.changedProperties.takeIf { it.isNotEmpty() }?.joinToString(", ", " · ") { propertyLabels[it] ?: it }.orEmpty()
            val left = detail.originalIndex?.let { before[it] }.orEmpty()
            val right = detail.successorIndex?.let { after[it] }.orEmpty()
            val position = listOfNotNull(detail.originalIndex?.let { "원본 ${it + 1}번" }, detail.successorIndex?.let { "새 기록 ${it + 1}번" })
                .joinToString(" → ") + if (detail.moved && detail.change != ItemChange.MOVED) " · 순서도 변경" else ""
            ComparisonDetailView(detail.change.label + properties, detail.change == ItemChange.AMBIGUOUS, position,
                highlight(left, right, "del"), highlight(right, left, "ins"))
        }, if (highlighted) highlight(originalText, successorText, "del") else listOf(DiffPart(originalText, null)),
            if (highlighted) highlight(successorText, originalText, "ins") else listOf(DiffPart(successorText, null)))
    }
    return view("comparison", "원본과 새 기록 비교", ComparisonView(result.successor.content.verifications.isEmpty(),
        recordUrl(result.original.id, searchUrl), result.original.version, recordUrl(result.successor.id, searchUrl),
        result.successor.version, Link(recordUrl(result.successor.id, searchUrl, "comparison", "changesOnly" to !changesOnly),
            if (changesOnly) "같은 항목도 함께 보기" else "변경된 항목만 보기"),
        if (changesOnly) "변경된 항목만 표시 중" else "전체 항목 표시 중", sections))
}

private const val EMPTY = "등록된 내용 없음"

private val ComparisonField.label: String get() = when (this) {
    ComparisonField.TITLE -> "제목"; ComparisonField.REQUEST -> "요청"; ComparisonField.DECISIONS -> "구현 결정과 출처"
    ComparisonField.CODE_ANCHORS -> "관련 코드"; ComparisonField.VERIFICATIONS -> "검증"; ComparisonField.OPEN_QUESTIONS -> "남은 질문"
    ComparisonField.BASE_REVISION -> "변경 전 커밋"; ComparisonField.TARGET_REVISION -> "변경 후 커밋"; ComparisonField.SNAPSHOT -> "스냅샷 해시"
}

private val ItemChange.label: String get() = when (this) {
    ItemChange.ADDED -> "추가"; ItemChange.REMOVED -> "삭제"; ItemChange.MODIFIED -> "내용 변경"
    ItemChange.MOVED -> "순서 변경"; ItemChange.AMBIGUOUS -> "중복 항목 · 비교 대상 불명확"
}

private val propertyLabels = mapOf("source" to "출처", "rationale" to "결정 이유", "summary" to "요약", "contentHash" to "줄 해시",
    "symbolName" to "심볼", "relatedPath" to "이름 변경 경로", "exitCode" to "종료 코드", "snapshotDigest" to "스냅샷 해시", "outputDigest" to "출력 해시")

private fun comparisonItems(field: ComparisonField, side: RecordComparisonSide): List<String> = with(side.content) {
    when (field) {
        ComparisonField.TITLE -> listOf(title)
        ComparisonField.REQUEST -> listOf(requestSummary)
        ComparisonField.DECISIONS -> decisions.map { "${it.source.label}\n${it.summary}\n${it.rationale.orEmpty()}" }
        ComparisonField.CODE_ANCHORS -> codeAnchors.map { "${it.side.label} ${it.relativePath}:${it.startLine}–${it.endLine}\n${it.symbolName.orEmpty()}\n줄 해시 ${it.contentHash}${it.relatedPath?.let { path -> "\n${if (it.side == CodeSide.BASE) "변경 후" else "변경 전"} 파일 경로 $path" }.orEmpty()}" }
        ComparisonField.VERIFICATIONS -> verifications.map { "${it.command}\n종료 코드 ${it.exitCode} · ${if (it.source == VerificationSource.LOCAL_RUNNER_REPORTED) "로컬 실행 도구 수집" else "클라이언트 제출"}\n${it.summary}\n${it.startedAt} ~ ${it.finishedAt}\n스냅샷 해시 ${it.snapshotDigest}\n출력 해시 ${it.outputDigest}" }
        ComparisonField.OPEN_QUESTIONS -> openQuestions
        ComparisonField.BASE_REVISION -> listOf(baseRevision.orEmpty())
        ComparisonField.TARGET_REVISION -> listOf(side.targetRevision.orEmpty())
        ComparisonField.SNAPSHOT -> listOf(snapshotDigest)
    }
}

/** 앞뒤가 같은 줄을 뺀 나머지 줄에 [mark](`del`·`ins`)를 붙인다. 줄바꿈은 표시 문자열로 남긴다. */
private fun highlight(value: String, other: String, mark: String): List<DiffPart> {
    if (value.isEmpty()) return listOf(DiffPart(EMPTY, null))
    if (value == other) return listOf(DiffPart(value, null))
    val lines = value.split('\n'); val otherLines = other.split('\n')
    val prefix = lines.zip(otherLines).takeWhile { it.first == it.second }.size
    val suffix = lines.drop(prefix).asReversed().zip(otherLines.drop(prefix).asReversed()).takeWhile { it.first == it.second }.size
    return lines.flatMapIndexed { index, line ->
        listOfNotNull(DiffPart("\n", null).takeIf { index > 0 }, DiffPart(line, mark.takeIf { index >= prefix && index < lines.size - suffix }))
    }
}

data class PublicationsView(val items: List<PublicationView>, val truncated: Boolean, val noticeNeeded: Boolean)

data class PublicationView(val pullRequest: Link, val status: String, val checkRunUrl: String?, val noticeNeeded: Boolean)

data class PullRequestsView(val repository: String, val number: String, val result: PullRequestResultView?)

data class PullRequestResultView(val pullNumber: Int, val checkedAt: TimeView, val headRevision: String, val contentUrl: String,
    val ciUrl: String, val fork: Boolean, val items: List<PullRequestItemView>, val nextUrl: String?)

data class PullRequestItemView(val match: String, val record: Link, val summary: String, val publication: String, val publishedAt: TimeView?)

data class ConnectionView(val repository: String, val number: String, val revision: String, val result: ConnectionResultView?)

data class ConnectionResultView(val checkedAt: TimeView, val checks: List<ConnectionCheckView>)

data class ConnectionCheckView(val status: String, val name: String, val message: String)

data class ComparisonView(val missingVerifications: Boolean, val originalUrl: String, val originalVersion: Long,
    val successorUrl: String, val successorVersion: Long, val filter: Link, val filterState: String,
    val sections: List<ComparisonSectionView>)

/** [details]가 있으면 전체 내용은 접어서 표시한다. */
data class ComparisonSectionView(val label: String, val state: String, val details: List<ComparisonDetailView>,
    val original: List<DiffPart>, val successor: List<DiffPart>)

data class ComparisonDetailView(val heading: String, val ambiguous: Boolean, val position: String,
    val original: List<DiffPart>, val successor: List<DiffPart>)

/** 비교 본문 조각이다. [mark]가 `del`·`ins`이면 해당 태그로 강조한다. */
data class DiffPart(val text: String, val mark: String?)
