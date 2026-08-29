package io.intenttrace.identity.application

import io.intenttrace.config.GitHubProperties
import io.intenttrace.config.GitHubUserAuthorizationProperties
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertFailsWith

class GitHubOAuthFlowServiceTest {
    @Test
    fun `유효 시간이 지난 state는 code 교환 전에 거부한다`() {
        val clock = MutableClock(Instant.parse("2026-08-28T12:00:00Z"))
        val oauth = FakeOAuthGateway(clock)
        val flow = GitHubOAuthFlowService(
            oauthGateway = oauth,
            userAccessGateway = FakeUserAccessGateway,
            sessions = FakeSessionStore,
            properties = GitHubProperties(
                userAuthorization = GitHubUserAuthorizationProperties(stateTtl = Duration.ofMinutes(10)),
            ),
            clock = clock,
        )
        val start = flow.start()
        clock.advance(Duration.ofMinutes(11))

        assertFailsWith<GitHubOAuthStateException> {
            flow.complete("authorization-code", start.state, start.state, null)
        }
        kotlin.test.assertEquals(0, oauth.exchangeCount)
    }

    @Test
    fun `대기 state 상한에 도달하면 만료 전 새 승인을 거부한다`() {
        val clock = MutableClock(Instant.parse("2026-08-28T12:00:00Z"))
        val oauth = FakeOAuthGateway(clock)
        val flow = GitHubOAuthFlowService(
            oauthGateway = oauth,
            userAccessGateway = FakeUserAccessGateway,
            sessions = FakeSessionStore,
            properties = GitHubProperties(
                userAuthorization = GitHubUserAuthorizationProperties(
                    stateTtl = Duration.ofMinutes(10),
                    maxPendingStates = 2,
                ),
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

    private object FakeUserAccessGateway : GitHubUserAccessGateway {
        override fun authenticate(accessToken: String): ActorIdentity = ActorIdentity.github(42, "lim")

        override fun repositoryRole(accessToken: String, repository: GitHubRepository): RepositoryRole? =
            error("사용하지 않는 테스트 경로")
    }

    private object FakeSessionStore : GitHubUserSessionStore {
        override fun issue(actor: ActorIdentity, tokens: GitHubUserOAuthTokens): IssuedGitHubUserSession =
            error("사용하지 않는 테스트 경로")

        override fun resolve(sessionToken: String): GitHubUserSession = error("사용하지 않는 테스트 경로")
    }

    private class MutableClock(private var current: Instant) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
