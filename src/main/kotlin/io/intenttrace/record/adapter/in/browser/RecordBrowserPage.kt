package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.ChangeRecordPage
import io.intenttrace.record.application.RecordScope
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.PurposeSource
import io.intenttrace.record.domain.VerificationSource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Component
class RecordBrowserPage(private val properties: GitHubProperties) {
    fun login(returnTo: String, expired: Boolean): String = layout("기록 열람", null, """
        <section class="welcome"><span class="trace-mark" aria-hidden="true">↳</span>
        <h1>코드 변경 이유와 검증 결과를 확인하세요.</h1>
        <p>GitHub 계정으로 로그인해 팀의 변경 기록을 확인하세요.</p>
        ${if (expired) "<p class=\"notice\">연결이 만료됐습니다. 다시 로그인하면 보던 기록으로 돌아옵니다.</p>" else ""}
        <a class="button" href="${html(url("/auth/github/start", "returnTo" to returnTo))}">GitHub로 로그인</a>
        <p class="muted">접근 권한이 있는 저장소의 기록만 표시합니다.</p></section>
    """)

    fun search(actor: ActorIdentity, repository: String?, q: String?, scope: RecordScope, page: ChangeRecordPage?,
        status: ChangeRecordStatus? = null, path: String? = null, authorId: Long? = null): String =
        layout("기록 찾기", actor, buildString {
            append("<header class=\"page-heading\"><h1>변경 기록 찾기</h1><p>어떤 요청이었고, 왜 이렇게 바꿨는지 찾아보세요.</p></header>")
            append("<nav class=\"scope-tabs\" aria-label=\"조회 범위\">")
            RecordScope.entries.forEach { option ->
                val link = html(url("/records", "repositoryKey" to repository, "q" to q, "path" to path, "scope" to option.name))
                append("<a href=\"$link\" ${if (option == scope) "aria-current=\"page\"" else ""}>${if (option == RecordScope.MINE) "내 비공개 기록" else "팀 공개 기록"}</a>")
            }
            append("</nav>")
            val statuses = if (scope == RecordScope.MINE) listOf(ChangeRecordStatus.DRAFT, ChangeRecordStatus.AUTHOR_CONFIRMED, ChangeRecordStatus.DISCARDED)
                else listOf(ChangeRecordStatus.PUBLISHED, ChangeRecordStatus.SUPERSEDED)
            append("""
                <form action="/records" method="get" class="search-form record-search">
                <label>저장소<input name="repositoryKey" value="${html(repository.orEmpty())}" placeholder="owner/repository" required maxlength="255" autocapitalize="none" spellcheck="false"></label>
                <label class="keyword">검색어<input name="q" value="${html(q.orEmpty())}" placeholder="제목, 요청, 구현 결정·이유" maxlength="200"></label>
                <input type="hidden" name="scope" value="${scope.name}">
                <label>상태<select name="status"><option value="">${if (scope == RecordScope.MINE) "초안·작성자 확인" else "공개·대체 전체"}</option>${statuses.joinToString("") { "<option value=\"${it.name}\" ${if (it == status) "selected" else ""}>${it.label}</option>" }}</select></label>
                <label>파일 경로<input name="path" value="${html(path.orEmpty())}" placeholder="src/App.kt" autocapitalize="none" spellcheck="false"></label>
                ${if (scope == RecordScope.TEAM) "<label>작성자 GitHub ID<input name=\"authorId\" type=\"number\" min=\"1\" step=\"1\" value=\"${authorId ?: ""}\" placeholder=\"숫자 ID · 선택\"></label>" else ""}
                <button type="submit">검색</button></form>
            """.trimIndent())
            if (page == null) {
                append("<div class=\"empty\"><h2>저장소를 입력하세요</h2><p>검색어를 비워두면 최근 기록부터 볼 수 있습니다.</p></div>")
            } else {
                append("<div class=\"result-heading\"><h2>${if (scope == RecordScope.MINE) "내 비공개 기록" else "팀 공개 기록"}</h2><span>이 페이지 ${page.items.size}건</span></div>")
                if (page.items.isEmpty()) append("<div class=\"empty\"><h3>일치하는 기록이 없습니다</h3><p>검색어를 줄이거나 저장소와 조회 범위를 확인해 주세요.</p></div>")
                append("<ul class=\"records\">")
                page.items.forEach { record ->
                    append("""<li><div class="record-summary"><span class="status">${record.status.label}</span><h3><a href="/records/${record.id}">${html(record.title)}</a></h3><p>${html(record.requestSummary)}</p><div class="record-meta"><span>@${html(record.createdBy.login)}</span>${stamp(record.createdAt)}</div></div><span class="open-record" aria-hidden="true">↗</span></li>""")
                }
                append("</ul>")
                page.nextCursor?.let { cursor ->
                    append("<nav class=\"pagination\" aria-label=\"결과 페이지\"><a class=\"button secondary\" href=\"${html(url("/records", "repositoryKey" to repository, "q" to q, "scope" to scope.name, "status" to status?.name, "path" to path, "authorId" to authorId?.toString(), "cursor" to cursor))}\">다음 기록</a></nav>")
                }
            }
        })

    fun record(actor: ActorIdentity, record: ChangeRecord): String = layout(record.title, actor, buildString {
        append("<a class=\"back-link\" href=\"${html(url("/records", "repositoryKey" to record.repositoryKey, "scope" to if (record.isPrivate) "MINE" else "TEAM", "status" to if (record.status == ChangeRecordStatus.DISCARDED) "DISCARDED" else null))}\">${html(record.repositoryKey)} 기록 목록</a>")
        append("<header class=\"record-heading\"><span class=\"status\">${record.status.label}</span><h1>${html(record.title)}</h1></header>")
        record.derivedFromRecordId?.let { append("<aside class=\"notice\">이 기록의 <a href=\"/records/$it\">원본 공개 기록 읽기</a> · <a href=\"/records/${record.id}/comparison\">원본과 비교</a></aside>") }
        record.supersededBy?.let { append("<aside class=\"notice\">이 기록은 새 기록으로 대체됐습니다. <a href=\"/records/$it\">새 기록 읽기</a></aside>") }
        append("<div class=\"reading-layout\"><article>")
        append("<section><h2>요청</h2><p class=\"prose lead\">${html(record.requestSummary)}</p></section>")
        append("<section><h2>구현 결정과 이유</h2><ol class=\"decisions\">")
        record.decisions.forEach { decision ->
            append("<li><span class=\"source\">${decision.source.label}</span><h3>${html(decision.summary)}</h3>")
            decision.rationale?.let { append("<p class=\"prose\">${html(it)}</p>") }
            append("</li>")
        }
        append("</ol></section><section><h2>관련 코드</h2><ul class=\"evidence\">")
        record.codeAnchors.forEach { anchor ->
            val revision = if (anchor.side == CodeSide.BASE) record.baseRevision else record.targetRevision
            val label = "${anchor.relativePath}:${anchor.startLine}–${anchor.endLine}"
            val encodedPath = anchor.relativePath.split('/').joinToString("/") { UriUtils.encodePathSegment(it, Charsets.UTF_8) }
            val codeUrl = revision?.let { properties.userAuthorization.webBaseUrl.resolve("/${record.repositoryKey}/blob/$it/$encodedPath").toString() + "#L${anchor.startLine}-L${anchor.endLine}" }
            append("<li><span class=\"source\">${if (anchor.side == CodeSide.BASE) "변경 전" else "변경 후"}</span> ")
            append(if (codeUrl == null) "<span>${html(label)}</span>" else "<a href=\"${html(codeUrl)}\">${html(label)}</a>")
            anchor.symbolName?.let { append("<p>${html(it)}</p>") }
            anchor.relatedPath?.let { append("<p class=\"muted\">연결된 경로: ${html(it)}</p>") }
            append("</li>")
        }
        append("</ul><p class=\"muted\">코드 링크는 기록에 연결된 커밋을 엽니다. 코드 해시는 제출된 값이며, 서버 확인은 별도 요청으로 실행합니다.</p>")
        if (record.targetRevision != null) append("<a class=\"button secondary\" href=\"/records/${record.id}/evidence\">GitHub 코드와 비교</a>")
        append("</section>")
        append("<section><h2>등록된 검증 결과</h2>")
        if (record.verifications.isEmpty()) append("<p class=\"muted\">등록된 검증 결과가 없습니다.</p>")
        record.verifications.forEach { verification ->
            val status = if (!verification.isCurrentFor(record)) "다른 스냅샷의 결과" else if (verification.exitCode == 0) "통과" else "실패"
            append("<div class=\"verification\"><strong>$status</strong><pre>${html(verification.command)}</pre><p class=\"prose\">${html(verification.summary)}</p>")
            append("<p class=\"muted\">${if (verification.source == VerificationSource.LOCAL_RUNNER_REPORTED) "로컬 실행 도구에서 수집한 결과" else "클라이언트가 제출한 결과"} · 종료 코드 ${verification.exitCode}</p>")
            append("<details><summary>검증 시각과 해시</summary><dl><dt>시작</dt><dd>${stamp(verification.startedAt)}</dd><dt>종료</dt><dd>${stamp(verification.finishedAt)}</dd><dt>출력 해시</dt><dd class=\"hash\">${html(verification.outputDigest)}</dd></dl></details></div>")
        }
        append("<p class=\"muted\">서버는 테스트 실행 여부를 확인하지 않습니다.</p></section>")
        append("<section><h2>남은 질문</h2>")
        if (record.openQuestions.isEmpty()) append("<p class=\"muted\">등록된 질문이 없습니다.</p>")
        else append(record.openQuestions.joinToString("", "<ul>", "</ul>") { "<li class=\"prose\">${html(it)}</li>" })
        append("</section></article><aside class=\"record-facts\"><h2>기록 정보</h2><dl><dt>작성자</dt><dd>@${html(record.createdBy.login)}</dd><dt>생성</dt><dd>${stamp(record.createdAt)}</dd>")
        record.confirmedAt?.let { append("<dt>작성자 확인</dt><dd>${stamp(it)}</dd>") }
        record.publishedAt?.let { append("<dt>공개</dt><dd>${stamp(it)}</dd>") }
        append("<dt>연결된 커밋</dt><dd class=\"hash\">${html(record.targetRevision ?: "작성자 확인 전")}</dd><dt>스냅샷 해시</dt><dd class=\"hash\">${html(record.snapshotDigest)}</dd><dt>기록 ID</dt><dd class=\"hash\">${record.id}</dd></dl><p class=\"muted\">시각은 UTC 기준입니다.</p><a href=\"/records/${record.id}/activities\">기록 변경 이력</a></aside></div>")
    })

    fun error(message: String): String = layout("기록을 열 수 없습니다", null,
        "<section class=\"empty\"><h1>기록을 열 수 없습니다</h1><p>${html(message)}</p><a class=\"button\" href=\"/records\">기록 찾기로 이동</a></section>")

    internal fun layout(title: String, actor: ActorIdentity?, content: String): String = """
        <!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${html(title)} · IntentTrace</title><link rel="stylesheet" href="/assets/record-browser.css"></head>
        <body><a class="skip-link" href="#content">본문으로 이동</a><header class="site-header"><a class="brand" href="/records"><span aria-hidden="true">↳</span> IntentTrace</a>
        <nav aria-label="주 메뉴"><a href="/records">기록 찾기</a><a href="/records/history">파일·줄 조회</a><a href="/records/pull-requests">PR 기록</a><a href="/records/connection">연결 진단</a>${actor?.let { "<a href=\"/records/sessions\">내 연결</a><span>@${html(it.login)}</span><form action=\"/records/logout\" method=\"post\"><button class=\"text-button\">로그아웃</button></form>" }.orEmpty()}</nav></header>
        <main id="content">$content</main><footer>코드 변경 이유와 검증 결과를 기록합니다. 기록은 저장소 권한에 따라 표시됩니다.</footer></body></html>
    """.trimIndent()
}

fun browserResponse(body: String, status: Int = 200): ResponseEntity<String> = ResponseEntity.status(status)
    .contentType(MediaType("text", "html", Charsets.UTF_8)).cacheControl(CacheControl.noStore())
    .header("Referrer-Policy", "no-referrer").header("X-Content-Type-Options", "nosniff")
    .header("Content-Security-Policy", "default-src 'none'; style-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
    .body(body)

internal fun html(value: String): String = HtmlUtils.htmlEscape(value, "UTF-8")
internal fun url(path: String, vararg values: Pair<String, String?>): String = UriComponentsBuilder.fromPath(path).apply {
    values.filter { !it.second.isNullOrEmpty() }.forEach { (key, value) -> queryParam(key, "{$key}") }
}.encode().buildAndExpand(values.filter { !it.second.isNullOrEmpty() }.toMap()).toUriString()

private val ChangeRecord.isPrivate: Boolean get() = status !in setOf(ChangeRecordStatus.PUBLISHED, ChangeRecordStatus.SUPERSEDED)
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

private val displayedTime = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneOffset.UTC)
internal fun stamp(value: Instant): String = "<time datetime=\"$value\">${displayedTime.format(value)} UTC</time>"
