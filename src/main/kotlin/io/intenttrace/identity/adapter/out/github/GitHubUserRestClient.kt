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

        try {
            ActorIdentity.github(response.id, response.login)
        } catch (_: IllegalArgumentException) {
            throw GitHubIdentityApiException("GitHub 사용자 응답 값이 올바르지 않습니다.")
        }
    }

    override fun repositoryRole(
        accessToken: String,
        actor: ActorIdentity,
        repository: GitHubRepository,
    ): RepositoryRole? {
        try {
            val response = client.get()
                .uri(
                    "/repos/{owner}/{repository}/collaborators/{username}/permission",
                    repository.canonicalOwner,
                    repository.canonicalName,
                    actor.login,
                )
                .headers { it.setBearerAuth(accessToken) }
                .retrieve()
                .body(GitHubRepositoryPermissionResponse::class.java)
                ?: throw GitHubIdentityApiException("GitHub 저장소 권한 응답이 비어 있습니다.")

            val responseActor = try {
                ActorIdentity.github(response.user.id, response.user.login)
            } catch (_: IllegalArgumentException) {
                throw GitHubIdentityApiException("GitHub 저장소 권한 응답 값이 올바르지 않습니다.")
            }
            if (responseActor.subject != actor.subject) {
                throw GitHubIdentityApiException("GitHub 저장소 권한 응답 사용자가 현재 사용자와 일치하지 않습니다.")
            }
            return response.toRole()
        } catch (exception: RestClientResponseException) {
            if (exception.statusCode == HttpStatus.NOT_FOUND) return null
            throw mapResponseException("저장소 권한 조회", exception)
        } catch (_: RestClientException) {
            throw GitHubIdentityApiException("GitHub 저장소 권한 조회 요청을 완료하지 못했습니다.")
        }
    }

    private fun <T> safeCall(operation: String, call: () -> T): T {
        try {
            return call()
        } catch (exception: RestClientResponseException) {
            throw mapResponseException(operation, exception)
        } catch (_: RestClientException) {
            throw GitHubIdentityApiException("GitHub $operation 요청을 완료하지 못했습니다.")
        }
    }

    private fun mapResponseException(operation: String, exception: RestClientResponseException): RuntimeException =
        if (exception.statusCode == HttpStatus.UNAUTHORIZED) {
            GitHubUserAuthenticationException()
        } else {
            GitHubIdentityApiException("GitHub $operation 요청이 실패했습니다. HTTP ${exception.statusCode.value()}")
        }

    private fun GitHubRepositoryPermissionResponse.toRole(): RepositoryRole? = when {
        permission.equals("admin", ignoreCase = true) -> RepositoryRole.MAINTAINER
        permission.equals("write", ignoreCase = true) -> if (roleName.equals("maintain", ignoreCase = true)) {
            RepositoryRole.MAINTAINER
        } else {
            RepositoryRole.CONTRIBUTOR
        }
        permission.equals("read", ignoreCase = true) -> RepositoryRole.READER
        permission.equals("none", ignoreCase = true) -> null
        else -> throw GitHubIdentityApiException("GitHub 저장소 권한 응답 값이 올바르지 않습니다.")
    }

    companion object {
        private const val GITHUB_JSON = "application/vnd.github+json"
        private const val API_VERSION_HEADER = "X-GitHub-Api-Version"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class GitHubUserResponse(
    val id: Long,
    val login: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class GitHubRepositoryPermissionResponse(
    val permission: String,
    @JsonProperty("role_name") val roleName: String? = null,
    val user: GitHubUserResponse,
)
