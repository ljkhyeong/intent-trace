package io.intenttrace.identity.application

import io.intenttrace.TestGitHubUserAccessGateway
import io.intenttrace.identity.domain.ActorIdentity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RepositoryAccessServiceTest {
    @Test
    fun `같은 요청은 권한을 공유하고 새 요청은 회수된 권한을 다시 확인한다`() {
        val actor = ActorIdentity.github(42, "author")
        var request = GitHubUserSession(actor, "test-token", java.util.UUID.randomUUID())
        val gateway = TestGitHubUserAccessGateway()
        val service = RepositoryAccessService(CurrentGitHubUserSession { request }, gateway)
        service.requireReader("Acme/Repo")
        service.requireContributor("acme/repo")
        service.requireMaintainer("acme/repo")
        assertEquals(1, gateway.roleChecks.get())
        request = GitHubUserSession(actor, "test-token", java.util.UUID.randomUUID())
        gateway.role = null
        repeat(2) { assertFailsWith<RepositoryAccessDeniedException> { service.requireReader("acme/repo") } }
        assertEquals(2, gateway.roleChecks.get())
    }
}
