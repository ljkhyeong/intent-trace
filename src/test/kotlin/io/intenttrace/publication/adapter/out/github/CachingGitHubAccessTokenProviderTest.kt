package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubAppProperties
import io.intenttrace.config.GitHubProperties
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals

class CachingGitHubAccessTokenProviderTest {
    private val target = GitHubPullRequestTarget("acme", "intent-trace", 12)

    @Test
    fun `installation token을 재사용하고 만료 여유 시간에 들어오면 갱신한다`() {
        val clock = MutableClock(Instant.parse("2026-08-28T00:00:00Z"))
        var issued = 0
        val provider = CachingGitHubAccessTokenProvider(
            properties = GitHubProperties(
                app = GitHubAppProperties(refreshBeforeExpiry = Duration.ofMinutes(5)),
            ),
            tokenIssuer = GitHubInstallationTokenIssuer {
                issued += 1
                GitHubInstallationAccessToken("token-$issued", clock.instant().plus(Duration.ofHours(1)))
            },
            clock = clock,
        )

        assertEquals("token-1", provider.token(target))
        assertEquals("token-1", provider.token(target))

        clock.current = clock.instant().plus(Duration.ofMinutes(56))
        assertEquals("token-2", provider.token(target))
        assertEquals(2, issued)
    }

    @Test
    fun `고정 token이 있으면 GitHub App 발급을 사용하지 않는다`() {
        val provider = CachingGitHubAccessTokenProvider(
            properties = GitHubProperties(token = "fixed-token"),
            tokenIssuer = GitHubInstallationTokenIssuer { error("발급하면 안 됩니다.") },
            clock = Clock.systemUTC(),
        )

        assertEquals("fixed-token", provider.token(target))
        assertEquals(false, provider.invalidate(target, "fixed-token"))
    }

    private class MutableClock(
        var current: Instant,
    ) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }
}
