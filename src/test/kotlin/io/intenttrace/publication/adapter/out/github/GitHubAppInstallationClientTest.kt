package io.intenttrace.publication.adapter.out.github

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
