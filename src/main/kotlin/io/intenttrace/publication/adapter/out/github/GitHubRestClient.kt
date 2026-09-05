package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubProperties
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.publication.application.ForkPullRequestUnsupportedException
import io.intenttrace.publication.application.GitHubRepositoryMismatchException
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.publication.application.GitHubPullRequestGateway
import io.intenttrace.publication.application.UpsertGitHubCheckRunCommand
import io.intenttrace.publication.domain.GitHubCheckRun
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

@Component
class GitHubRestClient(
    restClientBuilder: RestClient.Builder,
    properties: GitHubProperties,
    private val tokenProvider: GitHubAccessTokenProvider,
) : GitHubPullRequestGateway {
    private val client = restClientBuilder
        .baseUrl(properties.apiBaseUrl.toString().trimEnd('/'))
        .defaultHeader(HttpHeaders.ACCEPT, GITHUB_JSON)
        .defaultHeader(API_VERSION_HEADER, properties.apiVersion)
        .build()

    override fun getHeadRevision(target: GitHubPullRequestTarget): String = safeCall("Pull Request 조회") {
        val response = authenticated(target) { token ->
            client.get()
                .uri(
                    "/repos/{owner}/{repository}/pulls/{pullNumber}",
                    target.owner,
                    target.repository,
                    target.pullNumber,
                )
                .headers { it.setBearerAuth(token) }
                .retrieve()
                .body(PullRequestResponse::class.java)
        }
            ?: throw GitHubApiException("GitHub Pull Request 응답이 비어 있습니다.")

        val base = response.base?.repo ?: throw GitHubApiException("GitHub PR의 base 저장소를 확인할 수 없습니다.")
        val head = response.head.repo ?: throw GitHubApiException("GitHub PR의 head 저장소를 확인할 수 없습니다.")
        if (base.id <= 0 || head.id <= 0) throw GitHubApiException("GitHub PR의 저장소 ID가 올바르지 않습니다.")
        val baseKey = GitHubRepository.parse(base.fullName).key
        if (baseKey != target.repositoryKey) {
            throw GitHubRepositoryMismatchException(target.repositoryKey, baseKey)
        }
        if (head.id != base.id || GitHubRepository.parse(head.fullName).key != baseKey) {
            throw ForkPullRequestUnsupportedException()
        }
        response.head.sha
    }

    override fun upsertCheckRun(command: UpsertGitHubCheckRunCommand): GitHubCheckRun {
        command.knownCheckRunId?.let { knownId ->
            val known = getCheckRun(command, knownId)
            if (known != null) {
                updateCheckRun(command, known.id, tolerateMissing = true)?.let { return it.toDomain() }
            }
        }

        val existing = findCheckRun(command)
        if (existing != null) {
            return checkNotNull(updateCheckRun(command, existing.id, tolerateMissing = false)).toDomain()
        }

        return createCheckRun(command).toDomain()
    }

    private fun getCheckRun(command: UpsertGitHubCheckRunCommand, checkRunId: Long): CheckRunResponse? {
        try {
            val response = authenticated(command.target) { token ->
                client.get()
                    .uri(
                        "/repos/{owner}/{repository}/check-runs/{checkRunId}",
                        command.target.owner,
                        command.target.repository,
                        checkRunId,
                    )
                    .headers { it.setBearerAuth(token) }
                    .retrieve()
                    .body(CheckRunResponse::class.java)
            }
                ?: throw GitHubApiException("GitHub Check Run 조회 응답이 비어 있습니다.")

            return response.takeIf {
                it.externalId == command.externalId && it.headSha.equals(command.headRevision, ignoreCase = true)
            }
        } catch (exception: RestClientResponseException) {
            if (exception.statusCode == HttpStatus.NOT_FOUND) {
                return null
            }
            throw safeResponseException("Check Run 조회", exception)
        } catch (exception: RestClientException) {
            throw GitHubApiException("GitHub Check Run 조회 요청을 완료하지 못했습니다.", exception)
        }
    }

    private fun findCheckRun(command: UpsertGitHubCheckRunCommand): CheckRunResponse? = safeCall("Check Run 조회") {
        for (page in 1..MAX_CHECK_RUN_PAGES) {
            val response = authenticated(command.target) { token ->
                client.get()
                    .uri { builder ->
                        builder
                            .path("/repos/{owner}/{repository}/commits/{revision}/check-runs")
                            .queryParam("check_name", CHECK_NAME)
                            .queryParam("filter", "all")
                            .queryParam("per_page", CHECK_RUN_PAGE_SIZE)
                            .queryParam("page", page)
                            .build(
                                command.target.owner,
                                command.target.repository,
                                command.headRevision,
                            )
                    }
                    .headers { it.setBearerAuth(token) }
                    .retrieve()
                    .body(CheckRunListResponse::class.java)
                }
                ?: throw GitHubApiException("GitHub Check Run 목록 응답이 비어 있습니다.")

            response.checkRuns.firstOrNull { it.externalId == command.externalId }?.let { return@safeCall it }
            if (response.checkRuns.size < CHECK_RUN_PAGE_SIZE) {
                return@safeCall null
            }
        }
        null
    }

    private fun createCheckRun(command: UpsertGitHubCheckRunCommand): CheckRunResponse = safeCall("Check Run 생성") {
        authenticated(command.target) { token ->
            client.post()
                .uri("/repos/{owner}/{repository}/check-runs", command.target.owner, command.target.repository)
                .headers { it.setBearerAuth(token) }
                .body(
                    CreateCheckRunRequest(
                        name = CHECK_NAME,
                        headSha = command.headRevision,
                        externalId = command.externalId,
                        status = "completed",
                        conclusion = "neutral",
                        output = CheckRunOutput(command.title, command.summary, command.markdown),
                    ),
                )
                .retrieve()
                .body(CheckRunResponse::class.java)
        }
            ?: throw GitHubApiException("GitHub Check Run 생성 응답이 비어 있습니다.")
    }

    private fun updateCheckRun(
        command: UpsertGitHubCheckRunCommand,
        checkRunId: Long,
        tolerateMissing: Boolean,
    ): CheckRunResponse? {
        try {
            return authenticated(command.target) { token ->
                client.patch()
                    .uri(
                        "/repos/{owner}/{repository}/check-runs/{checkRunId}",
                        command.target.owner,
                        command.target.repository,
                        checkRunId,
                    )
                    .headers { it.setBearerAuth(token) }
                    .body(
                        UpdateCheckRunRequest(
                            name = CHECK_NAME,
                            externalId = command.externalId,
                            status = "completed",
                            conclusion = "neutral",
                            output = CheckRunOutput(command.title, command.summary, command.markdown),
                        ),
                    )
                    .retrieve()
                    .body(CheckRunResponse::class.java)
            }
                ?: throw GitHubApiException("GitHub Check Run 수정 응답이 비어 있습니다.")
        } catch (exception: RestClientResponseException) {
            if (tolerateMissing && exception.statusCode == HttpStatus.NOT_FOUND) {
                return null
            }
            throw safeResponseException("Check Run 수정", exception)
        } catch (exception: RestClientException) {
            throw GitHubApiException("GitHub Check Run 수정 요청을 완료하지 못했습니다.", exception)
        }
    }

    private fun <T> authenticated(target: GitHubPullRequestTarget, call: (String) -> T): T {
        val token = tokenProvider.token(target)
        try {
            return call(token)
        } catch (exception: RestClientResponseException) {
            if (exception.statusCode == HttpStatus.UNAUTHORIZED && tokenProvider.invalidate(target, token)) {
                return call(tokenProvider.token(target))
            }
            throw exception
        }
    }

    private fun <T> safeCall(operation: String, call: () -> T): T {
        try {
            return call()
        } catch (exception: GitHubApiException) {
            throw exception
        } catch (exception: RestClientResponseException) {
            throw safeResponseException(operation, exception)
        } catch (exception: RestClientException) {
            throw GitHubApiException("GitHub $operation 요청을 완료하지 못했습니다.", exception)
        }
    }

    private fun safeResponseException(operation: String, exception: RestClientResponseException): GitHubApiException =
        GitHubApiException("GitHub $operation 요청이 실패했습니다. HTTP ${exception.statusCode.value()}")

    private fun CheckRunResponse.toDomain(): GitHubCheckRun = GitHubCheckRun(id, htmlUrl)

    companion object {
        private const val GITHUB_JSON = "application/vnd.github+json"
        private const val API_VERSION_HEADER = "X-GitHub-Api-Version"
        private const val CHECK_NAME = "IntentTrace / 변경 의도"
        private const val CHECK_RUN_PAGE_SIZE = 100
        private const val MAX_CHECK_RUN_PAGES = 10
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PullRequestResponse(
    val head: PullRequestHeadResponse,
    val base: PullRequestHeadResponse? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PullRequestHeadResponse(
    val sha: String = "",
    val repo: PullRequestRepositoryResponse? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PullRequestRepositoryResponse(
    val id: Long,
    @JsonProperty("full_name") val fullName: String,
)

private data class CreateCheckRunRequest(
    val name: String,
    @JsonProperty("head_sha") val headSha: String,
    @JsonProperty("external_id") val externalId: String,
    val status: String,
    val conclusion: String,
    val output: CheckRunOutput,
)

private data class UpdateCheckRunRequest(
    val name: String,
    @JsonProperty("external_id") val externalId: String,
    val status: String,
    val conclusion: String,
    val output: CheckRunOutput,
)

private data class CheckRunOutput(
    val title: String,
    val summary: String,
    val text: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class CheckRunListResponse(
    @JsonProperty("check_runs") val checkRuns: List<CheckRunResponse>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class CheckRunResponse(
    val id: Long,
    @JsonProperty("head_sha") val headSha: String,
    @JsonProperty("html_url") val htmlUrl: String,
    @JsonProperty("external_id") val externalId: String?,
)
