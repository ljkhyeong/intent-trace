package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.publication.application.RecordPublications
import io.intenttrace.record.application.ChangeRecordPage
import io.intenttrace.record.application.RecordScope
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.PurposeSource
import io.intenttrace.record.domain.TEAM_VISIBLE_STATUSES
import io.intenttrace.record.domain.VerificationSource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 기록 화면의 표시 값을 만든다. 주소·문구·시각은 여기서 계산하고 `templates/records`는 값을 출력만 한다.
 * 템플릿은 `th:text`·`th:href`·`th:value`로 출력해 Thymeleaf가 이스케이프하며 `th:utext`는 쓰지 않는다.
 */
@Component
class RecordBrowserPage(private val properties: GitHubProperties) {
    fun login(returnTo: String, expired: Boolean): ModelAndView =
        view("login", "기록 열람", LoginView(url("/auth/github/start", "returnTo" to returnTo), expired))

    fun error(status: HttpStatus, message: String, retryUrl: String? = null): ModelAndView =
        view("error", "기록을 열 수 없습니다", ErrorView(message, retryUrl), status)

    fun search(actor: ActorIdentity, repository: String?, q: String?, scope: RecordScope, page: ChangeRecordPage?,
        status: ChangeRecordStatus?, path: String?, authorId: Long?, searchUrl: String): ModelAndView {
        val mine = scope == RecordScope.MINE
        val tabs = RecordScope.entries.map { option ->
            ScopeTab(url("/records", "repositoryKey" to repository, "q" to q, "path" to path, "scope" to option.name),
                scopeLabel(option), option == scope)
        }
        val statuses = scope.statuses.map { StatusOption(it.name, it.label, it == status) }
        val authorFilter = if (mine) null else {
            val mineOnly = authorId == actor.githubUserId()
            Link(url("/records", "repositoryKey" to repository, "q" to q, "path" to path, "scope" to scope.name,
                "status" to status?.name, "authorId" to if (mineOnly) null else actor.githubUserId().toString()),
                if (mineOnly) "작성자 필터 해제" else "내 공개 기록만 보기")
        }
        val result = page?.let {
            SearchResult(scopeLabel(scope), it.items.map { record ->
                RecordSummaryView(record.status.label, recordUrl(record.id, searchUrl), record.title, record.requestSummary,
                    record.createdBy.login, time(record.createdAt))
            }, it.nextCursor?.let { cursor ->
                url("/records", "repositoryKey" to repository, "q" to q, "scope" to scope.name, "status" to status?.name,
                    "path" to path, "authorId" to authorId?.toString(), "cursor" to cursor)
            })
        }
        return view("search", "기록 찾기", SearchView(tabs, repository.orEmpty(), q.orEmpty(), scope.name,
            if (mine) "초안·작성자 확인" else "공개·대체 전체", statuses, path.orEmpty(), !mine, authorId?.toString().orEmpty(),
            authorFilter, result))
    }

    fun record(publications: RecordPublications, searchUrl: String?): ModelAndView {
        val record = publications.record
        val backUrl = searchUrl ?: url("/records", "repositoryKey" to record.repositoryKey,
            "scope" to if (record.status in TEAM_VISIBLE_STATUSES) "TEAM" else "MINE",
            "status" to if (record.status == ChangeRecordStatus.DISCARDED) "DISCARDED" else null)
        val backLabel = when {
            searchUrl == null -> "${record.repositoryKey} 기록 목록"
            backUrl.substringBefore('?') == "/records/history" -> "파일·줄 조회로 돌아가기"
            backUrl.substringBefore('?') == "/records/pull-requests" -> "PR 기록으로 돌아가기"
            else -> "검색 결과로 돌아가기"
        }
        val anchors = record.codeAnchors.map { anchor ->
            val revision = if (anchor.side == CodeSide.BASE) record.baseRevision else record.targetRevision
            AnchorView(anchor.side.label, "${anchor.relativePath}:${anchor.startLine}–${anchor.endLine}",
                revision?.let { codeUrl(record.repositoryKey, it, anchor.relativePath, anchor.startLine, anchor.endLine) },
                anchor.symbolName, anchor.relatedPath)
        }
        val verifications = record.verifications.map {
            val result = if (!it.isCurrentFor(record)) "다른 스냅샷의 결과" else if (it.exitCode == 0) "통과" else "실패"
            val source = if (it.source == VerificationSource.LOCAL_RUNNER_REPORTED) "로컬 실행 도구에서 수집한 결과" else "클라이언트가 제출한 결과"
            VerificationView(result, it.command, it.summary, "$source · 종료 코드 ${it.exitCode}", time(it.startedAt),
                time(it.finishedAt), it.snapshotDigest, it.outputDigest)
        }
        return view("record", record.title, RecordView(Link(backUrl, backLabel), record.status.label, record.title,
            record.derivedFromRecordId?.let { recordUrl(it, searchUrl) },
            record.derivedFromRecordId?.let { recordUrl(record.id, searchUrl, "comparison") },
            record.supersededBy?.let { recordUrl(it, searchUrl) }, record.requestSummary,
            record.decisions.map { DecisionView(it.source.label, it.summary, it.rationale) }, anchors,
            record.targetRevision?.let { recordUrl(record.id, searchUrl, "evidence") }, verifications,
            record.targetRevision?.let { url("/records/github", "repositoryKey" to record.repositoryKey, "revision" to it) },
            record.openQuestions, record.createdBy.login, time(record.createdAt), record.confirmedAt?.let(::time),
            record.publishedAt?.let(::time), record.targetRevision ?: "작성자 확인 전", record.snapshotDigest, record.id.toString(),
            recordUrl(record.id, searchUrl, "activities"), "/records/${record.id}/markdown",
            if (record.status in TEAM_VISIBLE_STATUSES) publicationFacts(publications) else null))
    }

    internal fun codeUrl(repository: String, revision: String, path: String, startLine: Int, endLine: Int): String {
        val encodedPath = UriUtils.encodePath(path, Charsets.UTF_8)
        return properties.userAuthorization.webBaseUrl.resolve("/$repository/blob/$revision/$encodedPath").toString() + "#L$startLine-L$endLine"
    }

    private fun scopeLabel(scope: RecordScope) = if (scope == RecordScope.MINE) "내 비공개 기록" else "팀 공개 기록"
}

data class Link(val href: String, val label: String)

/** UTC 기준으로 표시하는 시각이다. */
data class TimeView(val iso: String, val display: String)

data class LoginView(val loginUrl: String, val expired: Boolean)

data class ErrorView(val message: String, val retryUrl: String?)

data class SearchView(val tabs: List<ScopeTab>, val repository: String, val q: String, val scope: String,
    val allStatuses: String, val statuses: List<StatusOption>, val path: String, val team: Boolean, val authorId: String,
    val authorFilter: Link?, val result: SearchResult?)

data class ScopeTab(val href: String, val label: String, val current: Boolean)

data class StatusOption(val value: String, val label: String, val selected: Boolean)

data class SearchResult(val heading: String, val items: List<RecordSummaryView>, val nextUrl: String?)

data class RecordSummaryView(val status: String, val href: String, val title: String, val summary: String,
    val author: String, val createdAt: TimeView)

data class RecordView(val back: Link, val status: String, val title: String, val originalUrl: String?,
    val comparisonUrl: String?, val successorUrl: String?, val requestSummary: String, val decisions: List<DecisionView>,
    val anchors: List<AnchorView>, val evidenceUrl: String?, val verifications: List<VerificationView>, val ciUrl: String?,
    val openQuestions: List<String>, val author: String, val createdAt: TimeView, val confirmedAt: TimeView?,
    val publishedAt: TimeView?, val targetRevision: String, val snapshotDigest: String, val id: String,
    val activitiesUrl: String, val markdownUrl: String, val publications: PublicationsView?)

data class DecisionView(val source: String, val summary: String, val rationale: String?)

data class AnchorView(val side: String, val label: String, val codeUrl: String?, val symbolName: String?, val relatedPath: String?)

data class VerificationView(val result: String, val command: String, val summary: String, val origin: String,
    val startedAt: TimeView, val finishedAt: TimeView, val snapshotDigest: String, val outputDigest: String)

/** 화면 모델에는 표시 값만 넣는다. 머리글의 로그인 이름은 [RecordBrowserController]의 공통 모델이 더한다. */
internal fun view(template: String, title: String, page: Any, status: HttpStatus = HttpStatus.OK) =
    ModelAndView("records/$template", mapOf("title" to title, "page" to page), status)

internal fun url(path: String, vararg values: Pair<String, String?>): String = UriComponentsBuilder.fromPath(path).apply {
    values.filter { !it.second.isNullOrEmpty() }.forEach { (key, value) -> queryParam(key, "{$key}") }
}.encode().buildAndExpand(values.filter { !it.second.isNullOrEmpty() }.toMap()).toUriString()

internal fun recordUrl(id: UUID, searchUrl: String?, section: String? = null, vararg controls: Pair<String, Any>): String =
    UriComponentsBuilder.fromUriString(searchUrl ?: "/records")
        .apply {
            when (build().path) {
                "/records/history" -> replaceQueryParam("from", "history")
                "/records/pull-requests" -> replaceQueryParam("from", "pull-requests")
            }
        }
        .replacePath("/records/$id${section?.let { "/$it" }.orEmpty()}")
        .apply { controls.forEach { (key, value) -> replaceQueryParam(key, value) } }
        .build().toUriString()

internal val ChangeRecordStatus.label: String get() = when (this) {
    ChangeRecordStatus.DRAFT -> "초안"
    ChangeRecordStatus.AUTHOR_CONFIRMED -> "작성자 확인"
    ChangeRecordStatus.PUBLISHED -> "팀 공개"
    ChangeRecordStatus.SUPERSEDED -> "대체됨"
    ChangeRecordStatus.DISCARDED -> "폐기됨"
}
internal val PurposeSource.label: String get() = when (this) {
    PurposeSource.STATED_BY_USER -> "사용자 요청"
    PurposeSource.STATED_IN_COMMIT -> "커밋에 명시"
    PurposeSource.CONFIRMED_AI_SUMMARY -> "작성자가 확인한 AI 요약"
    PurposeSource.INFERRED -> "정황에서 추론"
    PurposeSource.UNKNOWN -> "근거 미확인"
}
internal val CodeSide.label: String get() = if (this == CodeSide.BASE) "변경 전" else "변경 후"

private val displayedTime = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneOffset.UTC)
internal fun time(value: Instant) = TimeView(value.toString(), "${displayedTime.format(value)} UTC")
