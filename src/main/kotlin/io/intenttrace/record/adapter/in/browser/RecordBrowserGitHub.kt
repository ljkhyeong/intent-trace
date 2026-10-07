package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.record.application.GitHubActionsResults
import io.intenttrace.record.application.GitHubActionsRun
import io.intenttrace.record.application.GitHubRequestContext
import io.intenttrace.record.application.GitHubRequestKind
import org.springframework.web.servlet.ModelAndView

internal fun RecordBrowserPage.github(repository: String?, request: GitHubRequestContext?, actions: GitHubActionsResults?,
    actionsPage: Int): ModelAndView = view("github", "이슈·PR·CI", GitHubView(
    repository.orEmpty(), request?.number?.toString().orEmpty(), actions?.revision.orEmpty(),
    request?.let {
        GitHubRequestView(it.title, "${if (it.kind == GitHubRequestKind.ISSUE) "이슈" else "PR"} #${it.number}", it.sourceUrl,
            if (it.kind == GitHubRequestKind.PULL_REQUEST) {
                url("/records/pull-requests", "repositoryKey" to it.repositoryKey, "pullNumber" to it.number.toString())
            } else null,
            it.requestSummary, it.truncated, time(it.updatedAt), time(it.fetchedAt))
    },
    actions?.let { result ->
        fun pageUrl(page: Int) = url("/records/github", "repositoryKey" to result.repositoryKey,
            "revision" to result.revision, "page" to page.toString())
        GitHubActionsView(
            if (result.items.isNotEmpty()) null else if (actionsPage == 1) "조회된 CI 실행이 없습니다." else "이 페이지에 CI 실행이 없습니다. 이전 페이지에서 확인하세요.",
            result.items.map { run ->
                GitHubRunView(run.label, run.url, run.name.ifBlank { "워크플로 #${run.id}" },
                    "${run.attempt}차 실행 · ${if (run.event == "pull_request") "PR" else run.event}", run.startedAt?.let(::time), time(run.updatedAt))
            }, actionsPage, if (actionsPage > 1) pageUrl(actionsPage - 1) else null, result.nextPage?.let(::pageUrl),
            pageUrl(actionsPage), time(result.fetchedAt), result.searchLimited)
    }))

private val GitHubActionsRun.label: String get() = when (status) {
    "queued" -> "실행 대기"
    "in_progress" -> "실행 중"
    "completed" -> when (conclusion) {
        "success" -> "성공"; "failure" -> "실패"; "cancelled" -> "취소"; "skipped" -> "건너뜀"
        "timed_out" -> "시간 초과"; "neutral" -> "참고"; null -> "결과 미확인"
        else -> "결과: $conclusion"
    }
    else -> "진행 상태: $status"
}

data class GitHubView(val repository: String, val number: String, val revision: String, val request: GitHubRequestView?,
    val actions: GitHubActionsView?)

/** [recordsUrl]은 PR일 때만 있다. */
data class GitHubRequestView(val title: String, val label: String, val sourceUrl: String, val recordsUrl: String?,
    val requestSummary: String, val truncated: Boolean, val updatedAt: TimeView, val fetchedAt: TimeView)

data class GitHubActionsView(val emptyMessage: String?, val runs: List<GitHubRunView>, val page: Int, val previousUrl: String?,
    val nextUrl: String?, val refreshUrl: String, val fetchedAt: TimeView, val searchLimited: Boolean)

data class GitHubRunView(val status: String, val url: String, val name: String, val detail: String, val startedAt: TimeView?,
    val updatedAt: TimeView)
