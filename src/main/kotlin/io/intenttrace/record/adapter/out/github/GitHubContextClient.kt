package io.intenttrace.record.adapter.out.github

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.config.GitHubApiException
import io.intenttrace.config.readJsonWithin
import io.intenttrace.record.application.*
import io.intenttrace.record.domain.requireFullRevision
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Component
class GitHubContextClient(
    @Qualifier("githubApiRestClient") private val client: RestClient,
    private val session: CurrentGitHubUserSession,
    private val mapper: ObjectMapper,
    private val properties: GitHubProperties,
) : GitHubContextGateway {
    override fun request(repository: GitHubRepository, number: Int): GitHubRequestContent {
        val response = get(RequestResponse::class.java, "/repos/{owner}/{repository}/issues/{number}",
            repository.canonicalOwner, repository.canonicalName, number)
        val kind = if (response.pullRequest == null || response.pullRequest.isNull) GitHubRequestKind.ISSUE else GitHubRequestKind.PULL_REQUEST
        val path = if (kind == GitHubRequestKind.ISSUE) "issues" else "pull"
        val url = webUrl(repository, "$path/$number")
        if (response.number != number || !response.htmlUrl.equals(url, ignoreCase = true)) {
            throw GitHubApiException("GitHub 이슈·PR 응답이 요청 대상과 다릅니다.")
        }
        return GitHubRequestContent(kind, response.title, response.body, url, response.updatedAt)
    }

    override fun actions(repository: GitHubRepository, revision: String, page: Int): GitHubActionsPage {
        val response = get(ActionsResponse::class.java, "/repos/{owner}/{repository}/actions/runs?head_sha={revision}&per_page=20&page={page}",
            repository.canonicalOwner, repository.canonicalName, revision, page)
        if (response.totalCount < 0 || response.runs.size > 20) throw GitHubApiException("GitHub Actions 목록 크기가 올바르지 않습니다.")
        val runs = response.runs.map { run ->
            if (run.id <= 0 || run.runAttempt <= 0 || !run.repository.fullName.equals(repository.key, ignoreCase = true) || run.headSha != revision) {
                throw GitHubApiException("GitHub Actions 결과가 요청 저장소·커밋과 다릅니다.")
            }
            GitHubActionsRun(run.id, run.runAttempt, run.name.orEmpty(), requireFullRevision(run.headSha),
                run.event, run.status, run.conclusion, run.startedAt, run.updatedAt, webUrl(repository, "actions/runs/${run.id}"))
        }
        return GitHubActionsPage(response.totalCount, runs)
    }

    private fun webUrl(repository: GitHubRepository, path: String): String =
        properties.userAuthorization.webBaseUrl.resolve("/${repository.key}/$path").toString()

    // 값을 URI 템플릿 변수로 넘겨 호출 지표의 uri label에 저장소·커밋이 들어가지 않게 한다.
    private fun <T> get(type: Class<T>, template: String, vararg variables: Any): T = try {
        client.get().uri(template, *variables).headers { it.setBearerAuth(session.require().accessToken) }
            .exchange { _, response ->
                if (response.statusCode.value() == 401) throw GitHubUserAuthenticationException()
                if (response.statusCode.value() == 403) throw GitHubContextPermissionException()
                if (response.statusCode.value() in setOf(404, 410)) throw GitHubContextNotFoundException()
                if (!response.statusCode.is2xxSuccessful) throw GitHubApiException("GitHub 자료 조회 실패. HTTP ${response.statusCode.value()}")
                response.readJsonWithin(mapper, type, MAX_RESPONSE_SIZE) { throw GitHubApiException("GitHub 응답이 2 MiB를 초과했습니다.") }
            }
    } catch (_: RestClientException) {
        throw GitHubApiException("GitHub 자료를 조회하지 못했습니다.")
    }

    companion object { private const val MAX_RESPONSE_SIZE = 2 * 1024 * 1024 }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class RequestResponse(
    val number: Int, val title: String, val body: String?,
    @JsonProperty("html_url") val htmlUrl: String,
    @JsonProperty("updated_at") val updatedAt: Instant,
    @JsonProperty("pull_request") val pullRequest: JsonNode? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ActionsResponse(@JsonProperty("total_count") val totalCount: Int, @JsonProperty("workflow_runs") val runs: List<ActionRunResponse>)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ActionRunResponse(
    val id: Long, @JsonProperty("run_attempt") val runAttempt: Int, val name: String?,
    @JsonProperty("head_sha") val headSha: String, val event: String, val status: String, val conclusion: String?,
    @JsonProperty("run_started_at") val startedAt: Instant?, @JsonProperty("updated_at") val updatedAt: Instant,
    val repository: ActionsRepository,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ActionsRepository(@JsonProperty("full_name") val fullName: String)
