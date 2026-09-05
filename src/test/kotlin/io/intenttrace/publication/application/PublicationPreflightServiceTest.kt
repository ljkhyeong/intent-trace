package io.intenttrace.publication.application

import io.intenttrace.config.GitHubAppProperties
import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.*
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicationPreflightServiceTest {
    @Test
    fun `관리자 요청에서만 원격 점검하고 고정 token은 확인 완료로 표시하지 않는다`() {
        val actor = ActorIdentity.github(42, "author")
        var role = RepositoryRole.CONTRIBUTOR
        val access = RepositoryAccessService(object : CurrentGitHubUserSession { override fun require() = GitHubUserSession(actor, "test") },
            object : GitHubUserAccessGateway {
                override fun authenticate(accessToken: String) = actor
                override fun repositoryRole(accessToken: String, actor: ActorIdentity, repository: GitHubRepository) = role
            })
        var inspections = 0
        val inspector = PublicationCredentialInspector {
            inspections++
            PublicationCredentialInspection(1, null, listOf(PublicationCredentialCheck("permissions", PreflightStatus.VERIFIED, "테스트 응답")))
        }
        val properties = GitHubProperties(app = GitHubAppProperties("test-app", "test-key"))
        val service = PublicationPreflightService(access, inspector, properties, Clock.systemUTC())
        assertFailsWith<RepositoryAccessDeniedException> { service.check("acme/repo") }
        assertEquals(0, inspections)
        role = RepositoryRole.MAINTAINER
        assertTrue(service.check("Acme/Repo").ready)
        val fixed = PublicationPreflightService(access, inspector, properties.copy(token = "test-fixed"), Clock.systemUTC()).check("acme/repo")
        assertFalse(fixed.ready)
        assertEquals(1, inspections)
    }
}
