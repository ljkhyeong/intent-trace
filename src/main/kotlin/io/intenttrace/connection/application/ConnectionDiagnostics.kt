package io.intenttrace.connection.application

import io.intenttrace.config.GitHubProperties
import io.intenttrace.config.GitHubRateLimitException
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.RepositoryAccessDeniedException
import io.intenttrace.identity.application.RepositoryAccessService
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.publication.application.GitHubPullRequestReader
import io.intenttrace.publication.application.PullRequestSnapshot
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.application.GitEvidenceGateway
import io.intenttrace.record.domain.GitRevision
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
        val ref = revision?.let { GitRevision.parse(it).value }
        require(pullNumber == null || pullNumber > 0) { "PR 번호는 양수여야 합니다." }
        val checks = mutableListOf(ConnectionCheck("authentication", DiagnosticStatus.VERIFIED, "현재 요청의 GitHub 사용자 인증을 확인했습니다."))
        fun check(name: String, action: () -> Unit): Boolean = try {
            action()
            checks += ConnectionCheck(name, DiagnosticStatus.VERIFIED, "GitHub 응답으로 확인했습니다.")
            true
        } catch (exception: GitHubRateLimitException) {
            throw exception
        } catch (_: RepositoryAccessDeniedException) {
            checks += ConnectionCheck(name, DiagnosticStatus.FAILED, "대상 저장소의 팀 권한을 확인할 수 없습니다. GitHub App 설치와 사용자 접근 권한을 확인하세요.")
            false
        } catch (_: GitHubApiException) {
            checks += ConnectionCheck(name, DiagnosticStatus.FAILED, "GitHub 조회를 완료하지 못했습니다. 대상 번호·커밋과 App 읽기 권한을 확인하세요.")
            false
        } catch (_: GitHubIdentityApiException) {
            checks += ConnectionCheck(name, DiagnosticStatus.FAILED, "GitHub 권한 조회를 완료하지 못했습니다. 연결 상태를 확인하세요.")
            false
        }
        val readable = check("repository_read") { access.requireReader(repository.key) }
        if (readable) check("repository_write") { access.requireContributor(repository.key) }
        var pr: PullRequestSnapshot? = null
        if (readable && pullNumber != null) {
            check("pull_request_read") { pr = pullRequests.read(GitHubPullRequestTarget(repository.canonicalOwner, repository.canonicalName, pullNumber)) }
            pr?.let {
                checks += ConnectionCheck("pull_request_publication", if (it.fork) DiagnosticStatus.FAILED else DiagnosticStatus.VERIFIED,
                    if (it.fork) "Fork PR에는 Check Run을 게시할 수 없습니다." else "PR head와 base 저장소가 일치합니다.")
            }
        } else checks += ConnectionCheck("pull_request_read", DiagnosticStatus.NOT_CHECKED, "저장소 읽기 권한과 PR 번호가 필요합니다.")
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
