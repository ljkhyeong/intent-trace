package io.intenttrace.publication.application

import io.intenttrace.TestCurrentGitHubUserSession
import io.intenttrace.TestGitHubUserAccessGateway
import io.intenttrace.config.GitHubAppProperties
import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.*
import io.intenttrace.identity.domain.ActorIdentity
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
        val gateway = TestGitHubUserAccessGateway(RepositoryRole.CONTRIBUTOR)
        val access = RepositoryAccessService(TestCurrentGitHubUserSession(actor), gateway)
        var inspections = 0
        val inspector = PublicationCredentialInspector {
            inspections++
            PublicationCredentialInspection(1, null, listOf(PublicationCredentialCheck("permissions", PreflightStatus.VERIFIED, "테스트 응답")))
        }
        val properties = GitHubProperties(app = GitHubAppProperties("test-app", "test-key"))
        val service = PublicationPreflightService(access, inspector, properties, Clock.systemUTC())
        assertFailsWith<RepositoryAccessDeniedException> { service.check("acme/repo") }
        assertEquals(0, inspections)
        gateway.role = RepositoryRole.MAINTAINER
        assertTrue(service.check("Acme/Repo").ready)
        val fixed = PublicationPreflightService(access, inspector, properties.copy(token = "test-fixed"), Clock.systemUTC()).check("acme/repo")
        assertFalse(fixed.ready)
        assertEquals(1, inspections)
    }
}
