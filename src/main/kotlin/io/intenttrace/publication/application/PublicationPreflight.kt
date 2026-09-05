package io.intenttrace.publication.application

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.RepositoryAccessService
import io.intenttrace.identity.domain.GitHubRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

enum class PreflightStatus { VERIFIED, FAILED, CONFIGURED_UNVERIFIED, NOT_CONFIGURED, NOT_CHECKED }
data class PublicationCredentialCheck(val name: String, val status: PreflightStatus, val message: String)
data class PublicationCredentialInspection(val installationId: Long?, val expiresAt: Instant?, val checks: List<PublicationCredentialCheck>)
data class PublicationPreflight(val repositoryKey: String, val checkedAt: Instant, val ready: Boolean, val inspection: PublicationCredentialInspection)

fun interface PublicationCredentialInspector {
    fun inspect(repository: GitHubRepository): PublicationCredentialInspection
}

@Service
class PublicationPreflightService(
    private val access: RepositoryAccessService,
    private val inspector: PublicationCredentialInspector,
    private val properties: GitHubProperties,
    private val clock: Clock,
) {
    fun check(repositoryKey: String): PublicationPreflight {
        val repository = GitHubRepository.parse(repositoryKey)
        access.requireMaintainer(repository.key)
        val inspection = when {
            properties.token.isNotBlank() -> PublicationCredentialInspection(null, null, listOf(PublicationCredentialCheck(
                "fixed_token", PreflightStatus.CONFIGURED_UNVERIFIED, "고정 token을 사용 중입니다. 이 점검은 App 키로 발급하는 자격 증명만 원격 확인합니다.")))
            properties.app.clientId.isBlank() || properties.app.privateKeyBase64.isBlank() -> PublicationCredentialInspection(null, null,
                listOf(PublicationCredentialCheck("private_key", PreflightStatus.NOT_CONFIGURED, "App client ID와 private key를 설정해 주세요.")))
            else -> inspector.inspect(repository)
        }
        return PublicationPreflight(repository.key, Instant.now(clock), inspection.checks.isNotEmpty() && inspection.checks.all { it.status == PreflightStatus.VERIFIED }, inspection)
    }
}
