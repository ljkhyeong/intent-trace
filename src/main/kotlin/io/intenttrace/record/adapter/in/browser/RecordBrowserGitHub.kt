package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.GitHubActionsResults
import io.intenttrace.record.application.GitHubRequestContext
import io.intenttrace.record.application.GitHubRequestKind

internal fun RecordBrowserPage.github(actor: ActorIdentity, repository: String?, request: GitHubRequestContext?,
    actions: GitHubActionsResults?): String = layout("이슈·PR·CI", actor, buildString {
    append("<header class=\"page-heading\"><h1>이슈·PR·CI</h1><p>이슈·PR에서 초안 내용을 가져오거나 이미 실행된 CI 결과를 확인하세요.</p></header>")
    append("""<section><h2>이슈·PR 내용 가져오기</h2><form action="/records/github" class="search-form" method="get">
        <label>저장소<input name="repositoryKey" value="${html(repository.orEmpty())}" placeholder="owner/repository" required></label>
        <label>이슈·PR 번호<input name="number" type="number" min="1" value="${request?.number ?: ""}" required></label>
        <button>내용 가져오기</button></form>""")
    request?.let {
        append("<h3>${html(it.title)}</h3><p>${if (it.kind == GitHubRequestKind.ISSUE) "이슈" else "PR"} #${it.number} · <a href=\"${html(it.sourceUrl)}\">GitHub 원문</a></p>")
        append("<label>초안에 사용할 요청 내용<textarea rows=\"8\" readonly>${html(it.requestSummary)}</textarea></label>")
        append("<p class=\"muted\">가져온 내용을 검토한 뒤 Agent에서 초안을 만드세요. 작성자 확인과 공개는 별도로 진행합니다.</p>")
        if (it.truncated) append("<p class=\"notice\">길이 제한으로 일부만 표시합니다. 전체 내용은 원문에서 확인하세요.</p>")
        append("<p class=\"muted\">원문 수정 ${stamp(it.updatedAt)} · 조회 ${stamp(it.fetchedAt)}</p>")
    }
    append("</section><section><h2>GitHub Actions 결과</h2>")
    append("""<form action="/records/github" class="search-form" method="get">
        <label>저장소<input name="repositoryKey" value="${html(repository.orEmpty())}" placeholder="owner/repository" required></label>
        <label>커밋 해시(전체 길이)<input name="revision" value="${html(actions?.revision.orEmpty())}" required></label>
        <button>CI 결과 조회</button></form>""")
    actions?.let { result ->
        append("<p class=\"muted\">GitHub가 이 커밋에 연결한 실행 결과입니다. 실제 실행 코드와 로컬 테스트 결과는 확인하지 않습니다.</p>")
        if (result.items.isEmpty()) append("<p class=\"empty\">조회된 CI 실행이 없습니다.</p>")
        append("<ul class=\"records\">")
        result.items.forEach { run ->
            val status = when (run.status) {
                "queued" -> "실행 대기"
                "in_progress" -> "실행 중"
                "completed" -> when (run.conclusion) {
                    "success" -> "성공"; "failure" -> "실패"; "cancelled" -> "취소"; "skipped" -> "건너뜀"
                    "timed_out" -> "시간 초과"; "neutral" -> "참고"; null -> "결과 미확인"
                    else -> "결과: ${run.conclusion}"
                }
                else -> "진행 상태: ${run.status}"
            }
            val event = if (run.event == "pull_request") "PR" else run.event
            append("<li><div><span class=\"status\">${html(status)}</span><h3><a href=\"${html(run.url)}\">${html(run.name.ifBlank { "워크플로 #${run.id}" })}</a></h3>")
            append("<p>${run.attempt}차 실행 · ${html(event)}</p><p class=\"muted\">${run.startedAt?.let { "시작 ${stamp(it)} · " }.orEmpty()}최근 갱신 ${stamp(run.updatedAt)}</p></div></li>")
        }
        append("</ul><p class=\"muted\">조회 ${stamp(result.fetchedAt)}</p>")
        result.nextPage?.let { append("<a class=\"button secondary\" href=\"${html(url("/records/github", "repositoryKey" to result.repositoryKey, "revision" to result.revision, "page" to it.toString()))}\">다음 실행 결과</a>") }
        if (result.searchLimited) append("<p class=\"notice\">GitHub 검색 한도로 최대 1,000개까지만 조회할 수 있습니다. 전체 결과는 GitHub에서 확인하세요.</p>")
    }
    append("</section>")
})
