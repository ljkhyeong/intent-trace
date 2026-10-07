package io.intenttrace.publication.adapter.out.github

import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.config.GitHubApiException
import io.intenttrace.config.readJsonWithin
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.publication.application.GitHubPullRequestReader
import io.intenttrace.publication.application.PullRequestSnapshot
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper

@Component
class GitHubUserPullRequestClient(
    @Qualifier("githubApiRestClient") private val client: RestClient,
    private val session: CurrentGitHubUserSession,
    private val mapper: ObjectMapper,
) : GitHubPullRequestReader {
    override fun read(target: GitHubPullRequestTarget): PullRequestSnapshot = try {
        val repository = GitHubRepository(target.owner, target.repository)
        client.get().uri("/repos/{owner}/{repository}/pulls/{pullNumber}", repository.canonicalOwner, repository.canonicalName, target.pullNumber)
            .headers { it.setBearerAuth(session.require().accessToken) }
            .exchange { _, response ->
                if (response.statusCode.value() == 401) throw GitHubUserAuthenticationException()
                if (!response.statusCode.is2xxSuccessful) throw GitHubApiException("GitHub PR 조회 실패. HTTP ${response.statusCode.value()}")
                response.readJsonWithin(mapper, PullRequestResponse::class.java, 1024 * 1024) {
                    throw GitHubApiException("GitHub PR 응답이 허용 크기를 초과했습니다.")
                }.toSnapshot(target)
            }
    } catch (_: RestClientException) {
        throw GitHubApiException("GitHub PR 조회를 완료하지 못했습니다.")
    }
}
