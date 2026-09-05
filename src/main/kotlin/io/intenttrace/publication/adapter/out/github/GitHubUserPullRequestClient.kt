package io.intenttrace.publication.adapter.out.github

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.publication.application.GitHubPullRequestReader
import io.intenttrace.publication.application.GitHubRepositoryMismatchException
import io.intenttrace.publication.application.PullRequestSnapshot
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.domain.GitRevision
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper

@Component
class GitHubUserPullRequestClient(
    builder: RestClient.Builder,
    properties: GitHubProperties,
    private val session: CurrentGitHubUserSession,
    private val mapper: ObjectMapper,
) : GitHubPullRequestReader {
    private val client = builder.baseUrl(properties.apiBaseUrl.toString().trimEnd('/'))
        .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
        .defaultHeader("X-GitHub-Api-Version", properties.apiVersion).build()

    override fun read(target: GitHubPullRequestTarget): PullRequestSnapshot = try {
        client.get().uri("/repos/${target.repositoryKey}/pulls/${target.pullNumber}")
            .headers { it.setBearerAuth(session.require().accessToken) }
            .exchange { _, response ->
                if (response.statusCode.value() == 401) throw GitHubUserAuthenticationException()
                if (!response.statusCode.is2xxSuccessful) throw GitHubApiException("GitHub PR 조회 실패. HTTP ${response.statusCode.value()}")
                val bytes = response.body.readNBytes(1024 * 1024 + 1)
                if (bytes.size > 1024 * 1024) throw GitHubApiException("GitHub PR 응답이 허용 크기를 초과했습니다.")
                val pr = try { mapper.readValue(bytes, UserPullRequestResponse::class.java) } catch (_: RuntimeException) {
                    throw GitHubApiException("GitHub PR 응답을 해석할 수 없습니다.")
                }
                val base = pr.base.repo ?: throw GitHubApiException("GitHub PR의 base 저장소를 확인할 수 없습니다.")
                if (base.id <= 0) throw GitHubApiException("GitHub PR의 저장소 ID가 올바르지 않습니다.")
                val baseKey = GitHubRepository.parse(base.fullName).key
                if (baseKey != target.repositoryKey) throw GitHubRepositoryMismatchException(target.repositoryKey, baseKey)
                val head = pr.head.repo
                // 삭제된 Fork의 head 저장소도 게시할 수 없는 대상으로 표시한다.
                PullRequestSnapshot(GitRevision.parse(pr.head.sha).value,
                    head == null || head.id != base.id || GitHubRepository.parse(head.fullName).key != baseKey)
            }
    } catch (_: RestClientException) {
        throw GitHubApiException("GitHub PR 조회를 완료하지 못했습니다.")
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class UserPullRequestResponse(val head: UserPullRequestRef, val base: UserPullRequestRef)
@JsonIgnoreProperties(ignoreUnknown = true)
private data class UserPullRequestRef(val sha: String, val repo: UserPullRequestRepository? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
private data class UserPullRequestRepository(val id: Long, @JsonProperty("full_name") val fullName: String)
