package io.intenttrace.connection.application

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.RepositoryAccessDeniedException
import io.intenttrace.identity.application.RepositoryAccessService
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.config.GitHubApiException
import io.intenttrace.publication.application.GitHubPullRequestReader
import io.intenttrace.publication.application.GitHubRepositoryMismatchException
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.application.GitEvidenceGateway
import io.intenttrace.record.application.EvidenceUnavailableException
import io.intenttrace.record.domain.requireFullRevision
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

enum class DiagnosticStatus { VERIFIED, FAILED, CONFIGURED_UNVERIFIED, NOT_CONFIGURED, NOT_CHECKED }
data class ConnectionCheck(val name: String, val status: DiagnosticStatus, val message: String)
data class ConnectionDiagnosis(val repositoryKey: String, val checkedAt: Instant, val checks: List<ConnectionCheck>)

@Service
class ConnectionDiagnostics(
    private val access: RepositoryAccessService,
    private val evidence: GitEvidenceGateway,
    private val pullRequests: GitHubPullRequestReader,
    private val properties: GitHubProperties,
    private val clock: Clock,
) {
    fun diagnose(repositoryKey: String, revision: String? = null, pullNumber: Int? = null): ConnectionDiagnosis {
        val repository = GitHubRepository.parse(repositoryKey)
        val ref = revision?.let { requireFullRevision(it) }
        require(pullNumber == null || pullNumber > 0) { "PR 번호는 양수여야 합니다." }
        val checks = mutableListOf(ConnectionCheck("authentication", DiagnosticStatus.VERIFIED, "현재 요청의 GitHub 사용자 인증을 확인했습니다."))
        fun <T : Any> check(name: String, action: () -> T): T? = try {
            action().also { checks += ConnectionCheck(name, DiagnosticStatus.VERIFIED, "GitHub 응답으로 확인했습니다.") }
        } catch (error: RuntimeException) {
            // 코드 확인 불가는 GitHubApiException의 하위 예외라 먼저 구분한다.
            checks += ConnectionCheck(name, DiagnosticStatus.FAILED, when (error) {
                is RepositoryAccessDeniedException -> "대상 저장소의 접근 권한을 확인할 수 없습니다. GitHub App 설치와 사용자 권한을 확인하세요."
                is GitHubRepositoryMismatchException -> "PR의 병합 대상 저장소가 입력한 저장소와 다릅니다. 저장소 이름 변경이나 이전 여부를 확인하세요."
                is EvidenceUnavailableException -> error.reason.message
                is GitHubApiException -> "GitHub 조회를 완료하지 못했습니다. PR 번호·커밋 해시와 App 읽기 권한을 확인하세요."
                is GitHubIdentityApiException -> "GitHub 권한 조회를 완료하지 못했습니다. 연결 상태를 확인하세요."
                else -> throw error
            })
            null
        }
        val readable = check("repository_read") { access.requireReader(repository.key) } != null
        if (readable) check("repository_write") { access.requireContributor(repository.key) }
        val pr = if (readable && pullNumber != null) {
            check("pull_request_read") { pullRequests.read(GitHubPullRequestTarget(repository.canonicalOwner, repository.canonicalName, pullNumber)) }
        } else {
            checks += ConnectionCheck("pull_request_read", DiagnosticStatus.NOT_CHECKED, "저장소 읽기 권한과 PR 번호가 필요합니다.")
            null
        }
        if (pr != null) {
            checks += ConnectionCheck("pull_request_publication", if (pr.fork) DiagnosticStatus.FAILED else DiagnosticStatus.VERIFIED,
                if (pr.fork) "Fork PR에는 Check Run을 게시할 수 없습니다." else "PR 원본 저장소와 병합 대상 저장소가 같습니다.")
            if (ref != null) {
                val matches = ref == pr.headRevision
                checks += ConnectionCheck("pull_request_revision", if (matches) DiagnosticStatus.VERIFIED else DiagnosticStatus.FAILED,
                    if (matches) "입력한 커밋이 PR의 현재 커밋과 같습니다."
                    else "입력한 커밋이 PR의 현재 커밋과 다릅니다. PR의 최신 커밋으로 확인한 기록만 게시할 수 있습니다.")
            }
        }
        val evidenceRevision = ref ?: pr?.headRevision
        if (readable && evidenceRevision != null) check("git_tree_read") { evidence.snapshot(repository, evidenceRevision) }
        else checks += ConnectionCheck("git_tree_read", DiagnosticStatus.NOT_CHECKED, "저장소 읽기 권한과 커밋 해시 또는 PR 번호가 필요합니다.")
        val publishingConfigured = properties.token.isNotBlank() || (properties.app.clientId.isNotBlank() && properties.app.privateKeyBase64.isNotBlank())
        checks += ConnectionCheck("publication_credentials",
            if (publishingConfigured) DiagnosticStatus.CONFIGURED_UNVERIFIED else DiagnosticStatus.NOT_CONFIGURED,
            if (publishingConfigured) "GitHub 게시 인증이 설정돼 있습니다. 키 유효성과 설치·Checks 쓰기 권한은 확인하지 않았습니다."
            else "운영자가 서버 게시용 GitHub App client ID와 private key를 설정해야 합니다.")
        return ConnectionDiagnosis(repository.key, Instant.now(clock), checks)
    }
}
