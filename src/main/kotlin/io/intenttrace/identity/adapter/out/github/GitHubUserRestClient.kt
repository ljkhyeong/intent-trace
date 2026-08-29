package io.intenttrace.identity.adapter.out.github

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.GitHubUserAccessGateway
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

@Component
class GitHubUserRestClient(
    restClientBuilder: RestClient.Builder,
    properties: GitHubProperties,
) : GitHubUserAccessGateway {
    private val client = restClientBuilder
        .baseUrl(properties.apiBaseUrl.toString().trimEnd('/'))
        .defaultHeader(HttpHeaders.ACCEPT, GITHUB_JSON)
        .defaultHeader(API_VERSION_HEADER, properties.apiVersion)
        .build()

    override fun authenticate(accessToken: String): ActorIdentity = safeCall("사용자 조회") {
        val response = client.get()
            .uri("/user")
            .headers { it.setBearerAuth(accessToken) }
            .retrieve()
            .body(GitHubUserResponse::class.java)
            ?: throw GitHubIdentityApiException("GitHub 사용자 응답이 비어 있습니다.")

        ActorIdentity.github(response.id, response.login)
    }

    override fun repositoryRole(accessToken: String, repository: GitHubRepository): RepositoryRole? {
        try {
            for (page in 1..MAX_REPOSITORY_PAGES) {
                val response = client.get()
                    .uri { builder ->
                        builder.path("/user/repos")
                            .queryParam("affiliation", REPOSITORY_AFFILIATIONS)
                            .queryParam("per_page", REPOSITORIES_PER_PAGE)
                            .queryParam("page", page)
                            .build()
                    }
                    .headers { it.setBearerAuth(accessToken) }
                    .retrieve()
                    .toEntity(Array<GitHubRepositoryResponse>::class.java)
                val repositories = response.body
                    ?: throw GitHubIdentityApiException("GitHub 저장소 권한 응답이 비어 있습니다.")
                repositories.firstOrNull { it.fullName.equals(repository.key, ignoreCase = true) }
                    ?.let { return it.permissions.toRole() }
                if (!response.headers.hasNextPage()) return null
            }

            throw GitHubIdentityApiException("GitHub 저장소 권한 목록이 허용된 조회 범위를 초과했습니다.")
        } catch (exception: RestClientResponseException) {
            throw mapResponseException("저장소 권한 조회", exception)
        } catch (exception: RestClientException) {
            throw GitHubIdentityApiException("GitHub 저장소 권한 조회 요청을 완료하지 못했습니다.", exception)
        }
    }

    private fun <T> safeCall(operation: String, call: () -> T): T {
        try {
            return call()
        } catch (exception: GitHubIdentityApiException) {
            throw exception
        } catch (exception: RestClientResponseException) {
            throw mapResponseException(operation, exception)
        } catch (exception: RestClientException) {
            throw GitHubIdentityApiException("GitHub $operation 요청을 완료하지 못했습니다.", exception)
        }
    }

    private fun mapResponseException(operation: String, exception: RestClientResponseException): RuntimeException =
        if (exception.statusCode == HttpStatus.UNAUTHORIZED) {
            GitHubUserAuthenticationException()
        } else {
            GitHubIdentityApiException("GitHub $operation 요청이 실패했습니다. HTTP ${exception.statusCode.value()}")
        }

    private fun GitHubRepositoryPermissions.toRole(): RepositoryRole? = when {
        admin || maintain -> RepositoryRole.MAINTAINER
        push -> RepositoryRole.CONTRIBUTOR
        pull || triage -> RepositoryRole.READER
        else -> null
    }

    private fun HttpHeaders.hasNextPage(): Boolean =
        this[HttpHeaders.LINK].orEmpty().any { it.contains("rel=\"next\"") }

    companion object {
        private const val GITHUB_JSON = "application/vnd.github+json"
        private const val API_VERSION_HEADER = "X-GitHub-Api-Version"
        private const val REPOSITORY_AFFILIATIONS = "owner,collaborator,organization_member"
        private const val REPOSITORIES_PER_PAGE = 100
        private const val MAX_REPOSITORY_PAGES = 100
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class GitHubUserResponse(
    val id: Long,
    val login: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class GitHubRepositoryResponse(
    @JsonProperty("full_name") val fullName: String,
    val permissions: GitHubRepositoryPermissions = GitHubRepositoryPermissions(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class GitHubRepositoryPermissions(
    val pull: Boolean = false,
    val triage: Boolean = false,
    val push: Boolean = false,
    val maintain: Boolean = false,
    val admin: Boolean = false,
)
