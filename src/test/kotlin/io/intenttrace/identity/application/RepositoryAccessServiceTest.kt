package io.intenttrace.identity.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RepositoryAccessServiceTest {
    @Test
    fun `같은 요청은 권한을 공유하고 새 요청은 회수된 권한을 다시 확인한다`() {
        val actor = ActorIdentity.github(42, "author")
        var request = GitHubUserSession(actor, "test-token")
        var role: RepositoryRole? = RepositoryRole.MAINTAINER
        var reads = 0
        val service = RepositoryAccessService(object : CurrentGitHubUserSession { override fun require() = request },
            object : GitHubUserAccessGateway {
                override fun authenticate(accessToken: String) = actor
                override fun repositoryRole(accessToken: String, repository: GitHubRepository): RepositoryRole? { reads++; return role }
            })
        service.requireReader("Acme/Repo")
        service.requireContributor("acme/repo")
        service.requireMaintainer("acme/repo")
        assertEquals(1, reads)
        request = GitHubUserSession(actor, "test-token")
        role = null
        repeat(2) { assertFailsWith<RepositoryAccessDeniedException> { service.requireReader("acme/repo") } }
        assertEquals(2, reads)
    }
}
