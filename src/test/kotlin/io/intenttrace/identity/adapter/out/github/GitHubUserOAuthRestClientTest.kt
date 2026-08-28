package io.intenttrace.identity.adapter.out.github

import io.intenttrace.config.GitHubAppProperties
import io.intenttrace.config.GitHubProperties
import io.intenttrace.config.GitHubUserAuthorizationProperties
import io.intenttrace.identity.application.GitHubOAuthRefreshRejectedException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubUserOAuthRestClientTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = GitHubUserOAuthRestClient(builder, properties, fixedClock)

    @Test
    fun `승인 URL은 client와 정확한 callback과 state를 포함한다`() {
        val uri = client.authorizationUri("random-state", codeChallenge)
        val query = UriComponentsBuilder.fromUri(uri).build().queryParams

        assertEquals("https", uri.scheme)
        assertEquals("github.test", uri.host)
        assertEquals("/login/oauth/authorize", uri.path)
        assertEquals("client-id", query.getFirst("client_id"))
        assertEquals("https://intent.test/auth/github/callback", query.getFirst("redirect_uri"))
        assertEquals("random-state", query.getFirst("state"))
        assertEquals(codeChallenge, query.getFirst("code_challenge"))
        assertEquals("S256", query.getFirst("code_challenge_method"))
        assertEquals("select_account", query.getFirst("prompt"))
    }

    @Test
    fun `승인 code를 만료되는 access와 refresh token 쌍으로 교환한다`() {
        server.expect(requestTo("https://github.test/login/oauth/access_token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().formDataContains(
                    mapOf(
                        "client_id" to "client-id",
                        "client_secret" to "client-secret",
                        "code" to "authorization-code",
                        "redirect_uri" to "https://intent.test/auth/github/callback",
                        "code_verifier" to codeVerifier,
                    ),
                ),
            )
            .andRespond(withSuccess(tokenResponse("ghu_access-1", "ghr_refresh-1"), MediaType.APPLICATION_JSON))

        val tokens = client.exchange("authorization-code", codeVerifier)

        assertEquals("ghu_access-1", tokens.accessToken)
        assertEquals(now.plusSeconds(28_800), tokens.accessExpiresAt)
        assertEquals("ghr_refresh-1", tokens.refreshToken)
        assertEquals(now.plusSeconds(15_897_600), tokens.refreshExpiresAt)
        server.verify()
    }

    @Test
    fun `refresh token을 사용하면 새 token 쌍으로 교체한다`() {
        server.expect(requestTo("https://github.test/login/oauth/access_token"))
            .andExpect(
                content().formDataContains(
                    mapOf(
                        "grant_type" to "refresh_token",
                        "refresh_token" to "ghr_refresh-1",
                    ),
                ),
            )
            .andRespond(withSuccess(tokenResponse("ghu_access-2", "ghr_refresh-2"), MediaType.APPLICATION_JSON))

        val tokens = client.refresh("ghr_refresh-1")

        assertEquals("ghu_access-2", tokens.accessToken)
        assertEquals("ghr_refresh-2", tokens.refreshToken)
        server.verify()
    }

    @Test
    fun `거부된 refresh token은 재로그인 가능한 실패로 분류한다`() {
        server.expect(requestTo("https://github.test/login/oauth/access_token"))
            .andRespond(withSuccess("""{"error":"bad_refresh_token"}""", MediaType.APPLICATION_JSON))

        assertFailsWith<GitHubOAuthRefreshRejectedException> {
            client.refresh("ghr_expired")
        }
        server.verify()
    }

    private fun tokenResponse(accessToken: String, refreshToken: String): String =
        """
        {
          "access_token": "$accessToken",
          "expires_in": 28800,
          "refresh_token": "$refreshToken",
          "refresh_token_expires_in": 15897600,
          "token_type": "bearer"
        }
        """.trimIndent()

    companion object {
        private val now = Instant.parse("2026-08-28T12:00:00Z")
        private val fixedClock = Clock.fixed(now, ZoneOffset.UTC)
        private val codeChallenge = "c".repeat(43)
        private val codeVerifier = "v".repeat(43)
        private val properties = GitHubProperties(
            app = GitHubAppProperties(clientId = "client-id"),
            userAuthorization = GitHubUserAuthorizationProperties(
                webBaseUrl = URI.create("https://github.test"),
                clientSecret = "client-secret",
                callbackUrl = URI.create("https://intent.test/auth/github/callback"),
            ),
        )
    }
}
