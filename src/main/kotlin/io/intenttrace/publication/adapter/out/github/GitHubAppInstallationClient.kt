package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubProperties
import io.intenttrace.publication.application.*
import io.intenttrace.config.GitHubRateLimitException
import io.intenttrace.identity.domain.GitHubRepository
import java.time.Clock
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
    private val clock: Clock = Clock.systemUTC(),
) : GitHubInstallationTokenIssuer, PublicationCredentialInspector {
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
        GitHubInstallationAccessToken(response.token, Instant.parse(response.expiresAt))
    }

    override fun inspect(repository: GitHubRepository): PublicationCredentialInspection {
        val checks = mutableListOf<PublicationCredentialCheck>()
        var installationId: Long? = null
        var expiresAt: Instant? = null
        var stage = "private_key"
        val stages = listOf("private_key", "app_authentication", "installation", "token_issuance", "repository_scope", "permissions")
        fun verified(message: String) { checks += PublicationCredentialCheck(stage, PreflightStatus.VERIFIED, message) }
        try {
            val jwt = jwtProvider.create()
            verified("설정한 private key로 App JWT를 서명했습니다.")
            stage = "app_authentication"
            client.get().uri("/app").headers { it.setBearerAuth(jwt) }.retrieve().toBodilessEntity()
            verified("GitHub가 App JWT 인증을 수락했습니다.")
            stage = "installation"
            val installation = client.get().uri("/repos/{owner}/{repository}/installation", repository.canonicalOwner, repository.canonicalName)
                .headers { it.setBearerAuth(jwt) }.retrieve().body(InstallationResponse::class.java)
                ?: throw GitHubApiException("설치 응답이 없습니다.")
            check(installation.id > 0) { "설치 ID를 확인할 수 없습니다." }
            installationId = installation.id
            verified("대상 저장소의 App 설치를 확인했습니다.")
            stage = "token_issuance"
            val token = client.post().uri("/app/installations/{id}/access_tokens", installation.id)
                .headers { it.setBearerAuth(jwt) }
                .body(InstallationTokenRequest(listOf(repository.canonicalName), mapOf("pull_requests" to "read", "checks" to "write")))
                .retrieve().body(InstallationTokenResponse::class.java) ?: throw GitHubApiException("발급 응답이 없습니다.")
            check(token.token.isNotBlank()) { "발급 token이 없습니다." }
            expiresAt = Instant.parse(token.expiresAt)
            check(expiresAt.isAfter(Instant.now(clock))) { "발급 token이 만료됐습니다." }
            verified("대상 저장소로 제한한 token을 메모리에서 발급했습니다.")
            stage = "repository_scope"
            val scopeMatches = token.repositories?.map { it.fullName.lowercase() } == listOf(repository.key)
            checks += PublicationCredentialCheck(stage, if (scopeMatches) PreflightStatus.VERIFIED else PreflightStatus.FAILED,
                if (scopeMatches) "발급 응답의 저장소 범위가 대상 한 곳과 일치합니다." else "발급 응답에서 대상 저장소 한 곳으로 제한된 범위를 확인하지 못했습니다.")
            stage = "permissions"
            val permissionsMatch = token.permissions["checks"] == "write" && token.permissions["pull_requests"] in setOf("read", "write")
            checks += PublicationCredentialCheck(stage, if (permissionsMatch) PreflightStatus.VERIFIED else PreflightStatus.FAILED,
                if (permissionsMatch) "Checks 쓰기와 Pull requests 읽기 권한을 확인했습니다. 실제 게시 성공을 보장하지는 않습니다." else "발급 응답에서 Checks 쓰기 또는 Pull requests 읽기 권한을 확인하지 못했습니다.")
        } catch (exception: GitHubRateLimitException) {
            throw exception
        } catch (_: RuntimeException) {
            // 외부 응답·키 파싱 오류에는 자격 증명이 포함될 수 있어 원문을 내보내지 않는다.
            checks += PublicationCredentialCheck(stage, PreflightStatus.FAILED, "이 단계의 자격 증명을 확인하지 못했습니다. App 설정·설치 권한과 GitHub 연결을 확인해 주세요.")
        }
        stages.filter { name -> checks.none { it.name == name } }.forEach {
            checks += PublicationCredentialCheck(it, PreflightStatus.NOT_CHECKED, "앞 단계 실패로 실행하지 않았습니다.")
        }
        return PublicationCredentialInspection(installationId, expiresAt, checks)
    }

    private fun <T> safeCall(call: () -> T): T {
        try {
            return call()
        } catch (exception: GitHubApiException) {
            throw exception
        } catch (exception: RestClientResponseException) {
            throw GitHubApiException(
                "GitHub App installation token 발급 요청이 실패했습니다. HTTP ${exception.statusCode.value()}",
            )
        } catch (exception: RestClientException) {
            throw GitHubApiException("GitHub App installation token 발급 요청을 완료하지 못했습니다.", exception)
        } catch (_: java.time.format.DateTimeParseException) {
            throw GitHubApiException("GitHub App token 만료 시각 형식이 올바르지 않습니다.")
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
    @JsonProperty("expires_at") val expiresAt: String,
    val permissions: Map<String, String> = emptyMap(),
    val repositories: List<TokenRepository>? = null,
) {
    override fun toString(): String = "InstallationTokenResponse(token=[보호됨], expiresAt=$expiresAt)"
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TokenRepository(@JsonProperty("full_name") val fullName: String)
