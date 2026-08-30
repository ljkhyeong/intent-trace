package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubProperties
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.time.Instant

data class GitHubInstallationAccessToken(
    val value: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "GitHubInstallationAccessToken(value=[보호됨], expiresAt=$expiresAt)"
}

fun interface GitHubInstallationTokenIssuer {
    fun issue(target: GitHubPullRequestTarget): GitHubInstallationAccessToken
}

@Component
class GitHubAppInstallationClient(
    restClientBuilder: RestClient.Builder,
    private val properties: GitHubProperties,
    private val jwtProvider: GitHubAppJwtProvider,
) : GitHubInstallationTokenIssuer {
    private val client = restClientBuilder
        .baseUrl(properties.apiBaseUrl.toString().trimEnd('/'))
        .defaultHeader(HttpHeaders.ACCEPT, GITHUB_JSON)
        .defaultHeader(API_VERSION_HEADER, properties.apiVersion)
        .build()

    override fun issue(target: GitHubPullRequestTarget): GitHubInstallationAccessToken = safeCall {
        val jwt = jwtProvider.create()
        val installation = client.get()
            .uri("/repos/{owner}/{repository}/installation", target.owner, target.repository)
            .headers { it.setBearerAuth(jwt) }
            .retrieve()
            .body(InstallationResponse::class.java)
            ?: throw GitHubApiException("GitHub App 설치 조회 응답이 비어 있습니다.")

        val response = client.post()
            .uri("/app/installations/{installationId}/access_tokens", installation.id)
            .headers { it.setBearerAuth(jwt) }
            .body(
                InstallationTokenRequest(
                    repositories = listOf(target.repository),
                    permissions = mapOf(
                        "pull_requests" to "read",
                        "checks" to "write",
                    ),
                ),
            )
            .retrieve()
            .body(InstallationTokenResponse::class.java)
            ?: throw GitHubApiException("GitHub App token 발급 응답이 비어 있습니다.")

        if (response.token.isBlank()) {
            throw GitHubApiException("GitHub App token 발급 응답에 token이 없습니다.")
        }
        GitHubInstallationAccessToken(response.token, response.expiresAt)
    }

    private fun <T> safeCall(call: () -> T): T {
        try {
            return call()
        } catch (exception: RestClientResponseException) {
            throw GitHubApiException(
                "GitHub App installation token 발급 요청이 실패했습니다. HTTP ${exception.statusCode.value()}",
            )
        } catch (_: RestClientException) {
            throw GitHubApiException("GitHub App installation token 발급 요청을 완료하지 못했습니다.")
        }
    }

    companion object {
        private const val GITHUB_JSON = "application/vnd.github+json"
        private const val API_VERSION_HEADER = "X-GitHub-Api-Version"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class InstallationResponse(
    val id: Long,
)

private data class InstallationTokenRequest(
    val repositories: List<String>,
    val permissions: Map<String, String>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class InstallationTokenResponse(
    val token: String,
    @JsonProperty("expires_at") val expiresAt: Instant,
) {
    override fun toString(): String = "InstallationTokenResponse(token=[보호됨], expiresAt=$expiresAt)"
}
