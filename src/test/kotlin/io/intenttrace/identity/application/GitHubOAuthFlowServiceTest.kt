package io.intenttrace.identity.application

import io.intenttrace.MutableClock
import io.intenttrace.TestGitHubUserAccessGateway
import io.intenttrace.config.GitHubProperties
import io.intenttrace.config.GitHubUserAuthorizationProperties
import io.intenttrace.identity.domain.ActorIdentity
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GitHubOAuthFlowServiceTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `유효 시간이 지난 state는 code 교환 전에 거부하고 브라우저 복귀 주소만 남긴다`(browser: Boolean) {
        val clock = MutableClock(Instant.parse("2026-08-28T12:00:00Z"))
        val oauth = FakeOAuthGateway(clock)
        val flow = GitHubOAuthFlowService(
            oauthGateway = oauth,
            userAccessGateway = TestGitHubUserAccessGateway(),
            sessions = FakeSessionStore,
            properties = GitHubProperties(),
            clock = clock,
        )
        val returnTo = if (browser) "/records?scope=MINE" else null
        val start = flow.start(returnTo)
        clock.advance(Duration.ofMinutes(11))

        val failure = assertFailsWith<GitHubOAuthException> {
            flow.complete("authorization-code", start.state, start.state, null)
        }
        if (browser) {
            assertEquals(returnTo, assertIs<GitHubOAuthCallbackException>(failure).returnTo)
            assertIs<GitHubOAuthStateException>(failure.cause)
        } else {
            assertIs<GitHubOAuthStateException>(failure)
        }
        assertFailsWith<GitHubOAuthStateException> {
            flow.complete("authorization-code", start.state, start.state, null)
        }
        assertEquals(0, oauth.exchangeCount)
    }

    @Test
    fun `대기 state 상한에 도달하면 만료 전 새 승인을 거부한다`() {
        val clock = MutableClock(Instant.parse("2026-08-28T12:00:00Z"))
        val oauth = FakeOAuthGateway(clock)
        val flow = GitHubOAuthFlowService(
            oauthGateway = oauth,
            userAccessGateway = TestGitHubUserAccessGateway(),
            sessions = FakeSessionStore,
            properties = GitHubProperties(
                userAuthorization = GitHubUserAuthorizationProperties(maxPendingStates = 2),
            ),
            clock = clock,
        )
        flow.start()
        flow.start()

        assertFailsWith<GitHubOAuthCapacityException> { flow.start() }

        clock.advance(Duration.ofMinutes(11))
        flow.start()
    }

    private class FakeOAuthGateway(private val clock: Clock) : GitHubUserOAuthGateway {
        var exchangeCount = 0

        override fun authorizationUri(state: String, codeChallenge: String): URI =
            URI.create("https://github.test/authorize?state=$state&code_challenge=$codeChallenge")

        override fun exchange(code: String, codeVerifier: String): GitHubUserOAuthTokens {
            exchangeCount += 1
            return GitHubUserOAuthTokens(
                accessToken = "ghu_access",
                accessExpiresAt = clock.instant().plus(Duration.ofHours(8)),
                refreshToken = "ghr_refresh",
                refreshExpiresAt = clock.instant().plus(Duration.ofDays(180)),
            )
        }

        override fun refresh(refreshToken: String): GitHubUserOAuthTokens = error("사용하지 않는 테스트 경로")
    }

    private object FakeSessionStore : GitHubUserSessionStore {
        override fun issue(actor: ActorIdentity, tokens: GitHubUserOAuthTokens, channel: SessionChannel): IssuedGitHubUserSession =
            error("사용하지 않는 테스트 경로")

        override fun resolve(sessionToken: String): GitHubUserSession = error("사용하지 않는 테스트 경로")
        override fun revokeBrowser(sessionToken: String) = error("사용하지 않는 테스트 경로")
    }
}
