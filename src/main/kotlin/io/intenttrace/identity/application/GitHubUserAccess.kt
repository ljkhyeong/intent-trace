package io.intenttrace.identity.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import org.springframework.stereotype.Service
import java.util.UUID

class GitHubUserSession(
    val actor: ActorIdentity,
    val accessToken: String,
    val sessionId: UUID? = null,
) {
    // 인증할 때마다 새로 만드는 객체이며 장기 세션 저장소에는 보관하지 않는다.
    private val repositoryRoles = mutableMapOf<String, RepositoryRole?>()

    @Synchronized
    internal fun repositoryRole(repository: GitHubRepository, gateway: GitHubUserAccessGateway): RepositoryRole? {
        if (!repositoryRoles.containsKey(repository.key)) {
            repositoryRoles[repository.key] = gateway.repositoryRole(accessToken, repository)
        }
        return repositoryRoles[repository.key]
    }

    override fun toString(): String = "GitHubUserSession(actor=$actor, accessToken=[보호됨])"
}

interface CurrentGitHubUserSession {
    fun require(): GitHubUserSession
}

interface GitHubUserAccessGateway {
    fun authenticate(accessToken: String): ActorIdentity

    fun repositoryRole(accessToken: String, repository: GitHubRepository): RepositoryRole?
}

@Service
class RepositoryAccessService(
    private val currentSession: CurrentGitHubUserSession,
    private val gateway: GitHubUserAccessGateway,
) {
    fun requireReader(repositoryKey: String): ActorIdentity = require(repositoryKey, RepositoryRole.READER)

    fun requireContributor(repositoryKey: String): ActorIdentity = require(repositoryKey, RepositoryRole.CONTRIBUTOR)

    fun requireMaintainer(repositoryKey: String): ActorIdentity = require(repositoryKey, RepositoryRole.MAINTAINER)

    private fun require(repositoryKey: String, minimumRole: RepositoryRole): ActorIdentity {
        val repository = GitHubRepository.parse(repositoryKey)
        val session = currentSession.require()
        val role = session.repositoryRole(repository, gateway)
        if (role == null || !role.allows(minimumRole)) {
            throw RepositoryAccessDeniedException(repository.key, minimumRole)
        }
        return session.actor
    }
}

class GitHubUserAuthenticationException : RuntimeException("GitHub 사용자 인증에 실패했습니다.")

class GitHubIdentityApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class RepositoryAccessDeniedException(repositoryKey: String, requiredRole: RepositoryRole) :
    RuntimeException("저장소 $repositoryKey 에 ${requiredRole.name} 권한이 필요합니다.")
