package io.intenttrace.publication.adapter.out.github

import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.publication.application.PreflightStatus
import org.springframework.http.HttpStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import java.time.Clock
import java.time.ZoneOffset
import io.intenttrace.config.GitHubProperties
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubAppInstallationClientTest {
    @Test
    fun `사전 점검은 실제 발급 범위와 권한을 구분하고 token과 오류 원문은 반환하지 않는다`() {
        for (scenario in listOf("ok", "scope", "permissions", "rejected")) {
            val builder = RestClient.builder()
            val server = MockRestServiceServer.bindTo(builder).build()
            val clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC)
            val client = GitHubAppInstallationClient(builder, GitHubProperties(apiBaseUrl = URI("https://api.github.test")), GitHubAppJwtProvider { "synthetic-jwt" }, clock)
            server.expect(requestTo("https://api.github.test/app")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
            server.expect(requestTo("https://api.github.test/repos/acme/repo/installation"))
                .andRespond(withSuccess("""{"id":901}""", MediaType.APPLICATION_JSON))
            val request = server.expect(requestTo("https://api.github.test/app/installations/901/access_tokens"))
                .andExpect(content().json("""{"repositories":["repo"],"permissions":{"pull_requests":"read","checks":"write"}}""", JsonCompareMode.STRICT))
            if (scenario == "rejected") request.andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("synthetic-secret-error"))
            else request.andRespond(withSuccess("""{"token":"synthetic-secret-token","expires_at":"2026-09-05T01:00:00Z","repositories":[{"full_name":"${if (scenario == "scope") "acme/other" else "acme/repo"}"}],"permissions":{"checks":"${if (scenario == "permissions") "read" else "write"}","pull_requests":"read"}}""", MediaType.APPLICATION_JSON))
            val result = client.inspect(GitHubRepository.parse("acme/repo"))
            assertEquals(scenario == "ok", result.checks.all { it.status == PreflightStatus.VERIFIED })
            if (scenario != "rejected") assertEquals("GitHub App 토큰을 발급받았습니다.", result.checks.single { it.name == "token_issuance" }.message)
            if (scenario == "scope") assertEquals(PreflightStatus.FAILED, result.checks.single { it.name == "repository_scope" }.status)
            if (scenario == "permissions") assertEquals(PreflightStatus.FAILED, result.checks.single { it.name == "permissions" }.status)
            if (scenario == "rejected") assertEquals(PreflightStatus.NOT_CHECKED, result.checks.single { it.name == "permissions" }.status)
            assertFalse(result.toString().contains("synthetic-secret"))
            assertFalse(result.toString().contains("synthetic-jwt"))
            server.verify()
        }
    }

    @Test
    fun `저장소 설치를 찾아 필요한 권한만 가진 installation token을 발급한다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = GitHubAppInstallationClient(
            restClientBuilder = builder,
            properties = GitHubProperties(apiBaseUrl = URI.create("https://api.github.test")),
            jwtProvider = GitHubAppJwtProvider { "app-jwt" },
        )
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/installation"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer app-jwt"))
            .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
            .andRespond(withSuccess("""{"id":901}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.github.test/app/installations/901/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer app-jwt"))
            .andExpect(
                content().json(
                    """{"repositories":["intent-trace"],"permissions":{"pull_requests":"read","checks":"write"}}""",
                    JsonCompareMode.STRICT,
                ),
            )
            .andRespond(
                withSuccess(
                    """{"token":"installation-token","expires_at":"2026-08-28T01:00:00Z"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.issue(GitHubPullRequestTarget("acme", "intent-trace", 12))

        assertEquals("installation-token", result.value)
        assertEquals(Instant.parse("2026-08-28T01:00:00Z"), result.expiresAt)
        assertFalse(result.toString().contains("installation-token"))
        assertTrue(result.toString().contains("[보호됨]"))
        server.verify()
    }
}
