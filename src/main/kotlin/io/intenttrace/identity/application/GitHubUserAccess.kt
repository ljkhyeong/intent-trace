package io.intenttrace.identity.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import org.springframework.stereotype.Service

class GitHubUserSession(
    val actor: ActorIdentity,
    val accessToken: String,
) {
    override fun toString(): String = "GitHubUserSession(actor=$actor, accessToken=[보호됨])"
}

interface CurrentGitHubUserSession {
    fun require(): GitHubUserSession
}

interface GitHubUserAccessGateway {
    fun authenticate(accessToken: String): ActorIdentity

    fun repositoryRole(
        accessToken: String,
        actor: ActorIdentity,
        repository: GitHubRepository,
    ): RepositoryRole?
}

@Service
class RepositoryAccessService(
    private val currentSession: CurrentGitHubUserSession,
    private val gateway: GitHubUserAccessGateway,
) {
    fun requireReader(repositoryKey: String): ActorIdentity = require(repositoryKey, RepositoryRole.READER)

    fun requireContributor(repositoryKey: String): ActorIdentity = require(repositoryKey, RepositoryRole.CONTRIBUTOR)

    private fun require(repositoryKey: String, minimumRole: RepositoryRole): ActorIdentity {
        val repository = GitHubRepository.parse(repositoryKey)
        val session = currentSession.require()
        val role = gateway.repositoryRole(session.accessToken, session.actor, repository)
        if (role == null || !role.allows(minimumRole)) {
            throw RepositoryAccessDeniedException(repository.key, minimumRole)
        }
        return session.actor
    }
}

class GitHubUserAuthenticationException : RuntimeException("GitHub 사용자 인증에 실패했습니다.")

class GitHubIdentityApiException(message: String) : RuntimeException(message)

class RepositoryAccessDeniedException(repositoryKey: String, requiredRole: RepositoryRole) :
    RuntimeException("저장소 $repositoryKey 에 ${requiredRole.name} 권한이 필요합니다.")
