package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.IntentTraceApplication
import io.intenttrace.identity.application.GitHubUserAccessGateway
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.GitHubUserOAuthGateway
import io.intenttrace.identity.application.GitHubUserOAuthTokens
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [IntentTraceApplication::class, GitHubOAuthSessionIntegrationTest.OAuthTestConfiguration::class],
    properties = [
        "spring.datasource.url=jdbc:h2:mem:oauth-session-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.h2.console.enabled=false",
        "intent-trace.github.app.client-id=client-id",
        "intent-trace.github.user-authorization.client-secret=client-secret",
        "intent-trace.github.user-authorization.callback-url=http://127.0.0.1:8080/auth/github/callback",
    ],
)
@AutoConfigureMockMvc
class GitHubOAuthSessionIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val userAccess: TestGitHubUserAccessGateway,
) {
    @Test
    fun `GitHub callback에서 받은 로컬 session으로 MCP 도구를 호출한다`() {
        val start = mockMvc.get("/auth/github/start")
            .andExpect {
                status { isFound() }
                header { string(HttpHeaders.CACHE_CONTROL, containsString("no-store")) }
            }
            .andReturn()
        val stateCookie = start.response.cookies.single { it.name == GitHubOAuthController.STATE_COOKIE }
        val location = start.response.getHeader(HttpHeaders.LOCATION)
        assertNotNull(location)
        val state = UriComponentsBuilder.fromUriString(location).build().queryParams.getFirst("state")
        assertNotNull(state)
        assertTrue(stateCookie.isHttpOnly)
        assertFalse(stateCookie.secure)

        val callback = mockMvc.get("/auth/github/callback") {
            param("code", "authorization-code")
            param("state", state)
            cookie(stateCookie)
        }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.CACHE_CONTROL, containsString("no-store")) }
            header { string("Referrer-Policy", "no-referrer") }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
        }.andReturn()
        val body = callback.response.contentAsString
        val sessionToken = Regex("its_[A-Za-z0-9_-]{40,}").find(body)?.value
        assertNotNull(sessionToken)
        assertFalse(body.contains("ghu_access"))
        assertFalse(body.contains("ghr_refresh"))

        val initialized = mockMvc.post("/mcp") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $sessionToken")
            contentType = MediaType.APPLICATION_JSON
            header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
            content = initializeRequest
        }.andExpect {
            status { isOk() }
            jsonPath("$.result.serverInfo.name") { value("intent-trace") }
        }.andReturn()
        val mcpSessionId = initialized.response.getHeader("Mcp-Session-Id")
        assertNotNull(mcpSessionId)

        mockMvc.post("/mcp") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $sessionToken")
            header("Mcp-Session-Id", mcpSessionId)
            contentType = MediaType.APPLICATION_JSON
            header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
            content = findRequest
        }.andExpect {
            status { isOk() }
            content { string(containsString("\"isError\":false")) }
        }
    }

    @Test
    fun `callback state가 다르거나 재사용되면 session을 발급하지 않는다`() {
        val start = mockMvc.get("/auth/github/start").andReturn()
        val cookie = start.response.cookies.single { it.name == GitHubOAuthController.STATE_COOKIE }
        val location = start.response.getHeader(HttpHeaders.LOCATION)
        assertNotNull(location)
        val state = UriComponentsBuilder.fromUriString(location).build().queryParams.getFirst("state")
        assertNotNull(state)

        mockMvc.get("/auth/github/callback") {
            param("code", "authorization-code")
            param("state", "different-state")
            cookie(cookie)
        }.andExpect {
            status { isBadRequest() }
        }

        mockMvc.get("/auth/github/callback") {
            param("code", "authorization-code")
            param("state", state)
            cookie(cookie)
        }.andExpect {
            status { isOk() }
        }
        mockMvc.get("/auth/github/callback") {
            param("code", "authorization-code")
            param("state", state)
            cookie(cookie)
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `callback 사용자 조회 장애는 token을 노출하지 않는 보안 오류 화면을 반환한다`() {
        val start = mockMvc.get("/auth/github/start").andReturn()
        val cookie = start.response.cookies.single { it.name == GitHubOAuthController.STATE_COOKIE }
        val state = UriComponentsBuilder.fromUriString(start.response.getHeader(HttpHeaders.LOCATION)!!)
            .build()
            .queryParams
            .getFirst("state")!!
        userAccess.failAuthentication = true

        try {
            mockMvc.get("/auth/github/callback") {
                param("code", "authorization-code")
                param("state", state)
                cookie(cookie)
            }.andExpect {
                status { isBadGateway() }
                header { string(HttpHeaders.CACHE_CONTROL, containsString("no-store")) }
                header { string("Referrer-Policy", "no-referrer") }
                content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
                content { string(containsString("GitHub 승인 요청을 완료하지 못했습니다.")) }
            }
        } finally {
            userAccess.failAuthentication = false
        }
    }

    @TestConfiguration
    class OAuthTestConfiguration {
        @Bean
        @Primary
        fun gitHubUserOAuthGateway(clock: Clock): GitHubUserOAuthGateway = object : GitHubUserOAuthGateway {
            private var expectedChallenge: String? = null

            override fun authorizationUri(state: String, codeChallenge: String): URI {
                expectedChallenge = codeChallenge
                return URI.create("https://github.test/login/oauth/authorize?state=$state&code_challenge=$codeChallenge")
            }

            override fun exchange(code: String, codeVerifier: String): GitHubUserOAuthTokens {
                check(expectedChallenge == pkceChallenge(codeVerifier))
                return tokens(clock.instant(), "1")
            }

            override fun refresh(refreshToken: String): GitHubUserOAuthTokens = tokens(clock.instant(), "2")
        }

        @Bean
        @Primary
        fun gitHubUserAccessGateway(): TestGitHubUserAccessGateway = TestGitHubUserAccessGateway()

        private fun tokens(now: Instant, suffix: String): GitHubUserOAuthTokens = GitHubUserOAuthTokens(
            accessToken = "ghu_access-$suffix",
            accessExpiresAt = now.plus(Duration.ofHours(8)),
            refreshToken = "ghr_refresh-$suffix",
            refreshExpiresAt = now.plus(Duration.ofDays(180)),
        )

        private fun pkceChallenge(codeVerifier: String): String = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
            .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
    }

    class TestGitHubUserAccessGateway : GitHubUserAccessGateway {
        var failAuthentication = false

        override fun authenticate(accessToken: String): ActorIdentity {
            if (failAuthentication) throw GitHubIdentityApiException("테스트 사용자 조회 장애")
            return ActorIdentity.github(42, "lim")
        }

        override fun repositoryRole(
            accessToken: String,
            repository: GitHubRepository,
        ): RepositoryRole = RepositoryRole.MAINTAINER
    }

    companion object {
        private val initializeRequest =
            """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "initialize",
              "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "intent-trace-oauth-test", "version": "1.0"}
              }
            }
            """.trimIndent()
        private val findRequest =
            """
            {
              "jsonrpc": "2.0",
              "id": 2,
              "method": "tools/call",
              "params": {
                "name": "find_change_intent",
                "arguments": {
                  "repositoryKey": "acme/intent-trace",
                  "revision": "${"b".repeat(40)}",
                  "path": "src/App.kt",
                  "line": 1
                }
              }
            }
            """.trimIndent()
    }
}
