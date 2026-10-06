package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubApiException
import io.intenttrace.publication.application.*
import io.intenttrace.config.GitHubRateLimitException
import io.intenttrace.identity.domain.GitHubRepository
import java.time.Clock
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant

data class GitHubInstallationAccessToken(
    val value: String,
    val expiresAt: Instant,
    val installationId: Long,
) {
    override fun toString(): String = "GitHubInstallationAccessToken(value=[보호됨], expiresAt=$expiresAt, installationId=$installationId)"
}

fun interface GitHubInstallationTokenIssuer {
    fun issue(target: GitHubPullRequestTarget): GitHubInstallationAccessToken
}

@Component
class GitHubAppInstallationClient(
    @Qualifier("githubApiRestClient") private val client: RestClient,
    private val jwtProvider: GitHubAppJwtProvider,
    private val clock: Clock,
) : GitHubInstallationTokenIssuer, PublicationCredentialInspector {
    override fun issue(target: GitHubPullRequestTarget): GitHubInstallationAccessToken = safeCall("App installation token 발급") {
        val jwt = jwtProvider.create()
        val installationId = findInstallation(jwt, target.owner, target.repository).id
        val response = requestToken(jwt, installationId, target.repository)
        GitHubInstallationAccessToken(response.token, response.expiresAt, installationId)
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
            val installation = findInstallation(jwt, repository.canonicalOwner, repository.canonicalName)
            installationId = installation.id
            verified("대상 저장소의 App 설치를 확인했습니다.")
            stage = "token_issuance"
            val token = requestToken(jwt, installation.id, repository.canonicalName)
            expiresAt = token.expiresAt
            check(expiresAt.isAfter(Instant.now(clock)))
            verified("GitHub App 토큰을 발급받았습니다.")
            stage = "repository_scope"
            val scopeMatches = token.repositories?.map { it.fullName.lowercase() } == listOf(repository.key)
            checks += PublicationCredentialCheck(stage, if (scopeMatches) PreflightStatus.VERIFIED else PreflightStatus.FAILED,
                if (scopeMatches) "토큰 발급 응답의 저장소 목록이 대상 저장소 한 곳과 일치합니다." else "토큰 발급 응답에서 대상 저장소만 포함됐는지 확인하지 못했습니다.")
            stage = "permissions"
            val permissionsMatch = token.permissions["checks"] == "write" && token.permissions["pull_requests"] in setOf("read", "write")
            checks += PublicationCredentialCheck(stage, if (permissionsMatch) PreflightStatus.VERIFIED else PreflightStatus.FAILED,
                if (permissionsMatch) "Checks 쓰기와 Pull requests 읽기 권한을 확인했습니다. 실제 게시는 실행하지 않았습니다." else "토큰 발급 응답에서 Checks 쓰기 또는 Pull requests 읽기 권한을 확인하지 못했습니다.")
        } catch (exception: GitHubRateLimitException) {
            throw exception
        } catch (_: RuntimeException) {
            // 외부 응답·키 파싱 오류에는 자격 증명이 포함될 수 있어 원문을 내보내지 않는다.
            checks += PublicationCredentialCheck(stage, PreflightStatus.FAILED, "게시 인증 점검에 실패했습니다. GitHub App 설정·설치 권한과 연결 상태를 확인하세요.")
        }
        stages.filter { name -> checks.none { it.name == name } }.forEach {
            checks += PublicationCredentialCheck(it, PreflightStatus.NOT_CHECKED, "앞 단계 실패로 실행하지 않았습니다.")
        }
        return PublicationCredentialInspection(installationId, expiresAt, checks)
    }

    private fun findInstallation(jwt: String, owner: String, repository: String): InstallationResponse = client.get()
        .uri("/repos/{owner}/{repository}/installation", owner, repository)
        .headers { it.setBearerAuth(jwt) }
        .retrieve()
        .body(InstallationResponse::class.java)
        ?.takeIf { it.id > 0 } ?: throw GitHubApiException("GitHub App 설치 조회 응답이 올바르지 않습니다.")

    private fun requestToken(jwt: String, installationId: Long, repository: String): InstallationTokenResponse = client.post()
        .uri("/app/installations/{installationId}/access_tokens", installationId)
        .headers { it.setBearerAuth(jwt) }
        .body(InstallationTokenRequest(listOf(repository), mapOf("pull_requests" to "read", "checks" to "write")))
        .retrieve()
        .body(InstallationTokenResponse::class.java)
        ?.takeIf { it.token.isNotBlank() } ?: throw GitHubApiException("GitHub App token 발급 응답에 token이 없습니다.")
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
    val permissions: Map<String, String> = emptyMap(),
    val repositories: List<TokenRepository>? = null,
) {
    override fun toString(): String = "InstallationTokenResponse(token=[보호됨], expiresAt=$expiresAt)"
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TokenRepository(@JsonProperty("full_name") val fullName: String)
