package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.config.GitHubRateLimitException
import io.intenttrace.identity.application.GitHubUserAccessGateway
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.GitHubUserOAuthGateway
import io.intenttrace.identity.application.GitHubUserOAuthTokens
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.intenttrace.githubCallback
import io.intenttrace.htmlLink
import io.intenttrace.startGitHubLogin

@SpringBootTest(properties = ["intent-trace.github.app.client-id=client-id", "intent-trace.github.user-authorization.client-secret=client-secret"])
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
        assertEquals(stateCookie.value, state)
        assertTrue(stateCookie.isHttpOnly)
        assertFalse(stateCookie.secure)
        assertEquals("/auth/github/callback", stateCookie.path)
        assertEquals("Lax", stateCookie.getAttribute("SameSite"))
        assertEquals(600, stateCookie.maxAge)

        val callback = mockMvc.githubCallback(stateCookie).andExpect {
            status { isOk() }
            header { string(HttpHeaders.CACHE_CONTROL, containsString("no-store")) }
            header { string("Referrer-Policy", "no-referrer") }
            header { string("Content-Security-Policy", containsString("style-src 'self'")) }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
        }.andReturn()
        val expiredState = callback.response.cookies.single { it.name == GitHubOAuthController.STATE_COOKIE }
        assertEquals("", expiredState.value)
        assertEquals(0, expiredState.maxAge)
        assertEquals(stateCookie.path, expiredState.path)
        assertEquals(stateCookie.isHttpOnly, expiredState.isHttpOnly)
        assertEquals(stateCookie.secure, expiredState.secure)
        assertEquals(stateCookie.getAttribute("SameSite"), expiredState.getAttribute("SameSite"))
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

        val listed = mockMvc.get("/api/v1/me/sessions") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $sessionToken")
        }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.CACHE_CONTROL, containsString("no-store")) }
        }.andReturn().response.contentAsString
        assertFalse(listed.contains(sessionToken))
        assertFalse(listed.contains("ghu_access"))
        mockMvc.delete("/api/v1/me/sessions/current") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $sessionToken")
        }.andExpect { status { isOk() }; jsonPath("$.revokedCount") { value(1) } }
        mockMvc.get("/api/v1/me/sessions") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $sessionToken")
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `현재 로컬 session을 폐기하면 이후 요청은 인증되지 않고 GitHub 토큰 직접 인증은 받지 않는다`() {
        val sessionToken = issueSession()

        mockMvc.delete("/api/v1/me/sessions/current") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $sessionToken")
        }.andExpect { status { isOk() }; jsonPath("$.revokedCount") { value(1) } }

        for (token in listOf(sessionToken, "ghu_direct-access")) {
            mockMvc.get("/api/v1/me/sessions") {
                header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            }.andExpect { status { isUnauthorized() } }
        }
    }

    private fun issueSession(): String = Regex("its_[A-Za-z0-9_-]{40,}")
        .find(mockMvc.githubCallback(mockMvc.startGitHubLogin()).andReturn().response.contentAsString)!!.value

    @Test
    fun `callback state가 다르거나 재사용되면 session을 발급하지 않는다`() {
        val cookie = mockMvc.startGitHubLogin()
        // 형식이 맞는 다른 state로 쿠키 비교 단계까지 확인한다.
        mockMvc.githubCallback(cookie, state = differentState).andExpect { status { isBadRequest() } }
        mockMvc.githubCallback(cookie).andExpect { status { isOk() } }
        mockMvc.githubCallback(cookie).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `callback 사용자 조회 장애는 token을 노출하지 않는 보안 오류 화면을 반환한다`() {
        val cookie = mockMvc.startGitHubLogin()
        userAccess.authenticationFailure = GitHubIdentityApiException("테스트 사용자 조회 장애")

        try {
            mockMvc.githubCallback(cookie).andExpect {
                status { isBadGateway() }
                header { string(HttpHeaders.CACHE_CONTROL, containsString("no-store")) }
                header { string("Referrer-Policy", "no-referrer") }
                content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
                content { string(containsString("GitHub 인증 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.")) }
                content { string(containsString("href=\"/auth/github/start\">다시 로그인")) }
            }
        } finally {
            userAccess.authenticationFailure = null
        }
    }

    @ParameterizedTest
    @CsvSource("denied, 401", "code, 400", "identity, 502", "rate, 429")
    fun `브라우저 로그인 실패 후 재시도하면 같은 검색 화면으로 돌아온다`(failure: String, expectedStatus: Int) {
        val returnTo = "/records?repository=acme/demo&q=a%2Bb%26%22%3Ctag%3E&scope=MINE"
        val stateCookie = mockMvc.startGitHubLogin(returnTo)
        userAccess.authenticationFailure = when (failure) {
            "identity" -> GitHubIdentityApiException("테스트 사용자 조회 장애")
            "rate" -> GitHubRateLimitException(120)
            else -> null
        }
        val callback = try {
            mockMvc.githubCallback(stateCookie, code = if (failure == "code") null else "authorization-code") {
                if (failure == "denied") param("error", "access_denied")
                param("returnTo", "https://untrusted.example/records")
            }.andExpect {
                status { isEqualTo(expectedStatus) }
                header { string(HttpHeaders.CACHE_CONTROL, containsString("no-store")) }
                header { string("Referrer-Policy", "no-referrer") }
            }.andReturn().response
        } finally {
            userAccess.authenticationFailure = null
        }
        if (failure == "rate") assertEquals("120", callback.getHeader(HttpHeaders.RETRY_AFTER))
        assertTrue(callback.cookies.none { it.name == BROWSER_SESSION_COOKIE })
        assertFalse(callback.contentAsString.contains("untrusted.example"))
        assertFalse(callback.contentAsString.contains("its_"))
        assertFalse(callback.contentAsString.contains("ghu_"))
        assertEquals(0, callback.cookies.single { it.name == GitHubOAuthController.STATE_COOKIE }.maxAge)

        val retryUrl = URI(htmlLink(callback.contentAsString, "다시 로그인"))
        val retry = mockMvc.get(retryUrl).andExpect { status { isFound() } }.andReturn().response
        val newState = retry.cookies.single { it.name == GitHubOAuthController.STATE_COOKIE }
        mockMvc.githubCallback(stateCookie).andExpect { status { isBadRequest() } }
        val completed = mockMvc.githubCallback(newState).andExpect {
            status { isSeeOther() }
            header { string(HttpHeaders.LOCATION, returnTo) }
        }.andReturn().response
        assertTrue(completed.cookies.single { it.name == BROWSER_SESSION_COOKIE }.value.startsWith("itb_"))
        assertFalse(completed.contentAsString.contains("its_"))
    }

    @Test
    fun `확인되지 않은 state의 복귀 주소는 재로그인 링크에 사용하지 않는다`() {
        val stateCookie = mockMvc.startGitHubLogin("/records?scope=MINE")
        mockMvc.githubCallback(stateCookie, state = differentState) { param("returnTo", "https://untrusted.example/records") }.andExpect {
            status { isBadRequest() }
            content { string(containsString("href=\"/auth/github/start\">다시 로그인")) }
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
        var authenticationFailure: RuntimeException? = null
        val authentications = AtomicInteger()

        override fun authenticate(accessToken: String): ActorIdentity {
            authentications.incrementAndGet()
            authenticationFailure?.let { throw it }
            return ActorIdentity.github(42, "lim")
        }

        override fun repositoryRole(
            accessToken: String,
            actor: ActorIdentity,
            repository: GitHubRepository,
        ): RepositoryRole = RepositoryRole.MAINTAINER
    }

    companion object {
        private val differentState = "A".repeat(43)
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
