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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class InMemoryGitHubUserSessionStoreTest {
    private val clock = MutableClock(now)
    private val oauth = FakeGitHubUserOAuthGateway(clock)
    private val users = FakeGitHubUserAccessGateway()
    private val store = InMemoryGitHubUserSessionStore(oauth, users, properties, clock)

    @Test
    fun `만료 여유 시간 전에는 기존 access token을 사용한다`() {
        val issued = store.issue(owner, tokens(clock.instant(), "1", Duration.ofHours(8)))

        val resolved = store.resolve(issued.sessionToken)

        assertEquals("ghu_access-1", resolved.accessToken)
        assertEquals(0, oauth.refreshCount.get())
    }

    @Test
    fun `만료가 가까우면 token 쌍을 한 번 갱신한다`() {
        val issued = store.issue(owner, tokens(clock.instant(), "1", Duration.ofMinutes(4)))
        clock.advance(Duration.ofMinutes(1))

        val first = store.resolve(issued.sessionToken)
        val second = store.resolve(issued.sessionToken)

        assertEquals("ghu_access-2", first.accessToken)
        assertEquals("ghu_access-2", second.accessToken)
        assertEquals(1, oauth.refreshCount.get())
        assertEquals(clock.instant().plus(Duration.ofDays(180)), store.list(owner.subject).single().expiresAt)
    }

    @Test
    fun `동시에 갱신 구간에 들어와도 refresh token은 한 번만 사용한다`() {
        val issued = store.issue(owner, tokens(clock.instant(), "1", Duration.ofMinutes(4)))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map {
                executor.submit<String> { store.resolve(issued.sessionToken).accessToken }
            }.map { it.get(5, TimeUnit.SECONDS) }

            assertTrue(results.all { it == "ghu_access-2" })
            assertEquals(1, oauth.refreshCount.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `갱신 뒤 GitHub 사용자가 달라지면 session을 폐기한다`() {
        val issued = store.issue(owner, tokens(clock.instant(), "1", Duration.ofMinutes(4)))
        users.refreshedActor = ActorIdentity.github(84, "teammate")

        assertFailsWith<GitHubUserAuthenticationException> {
            store.resolve(issued.sessionToken)
        }
        assertFailsWith<GitHubUserAuthenticationException> {
            store.resolve(issued.sessionToken)
        }
    }

    @Test
    fun `refresh token이 만료된 session은 재로그인이 필요하다`() {
        val issued = store.issue(owner, tokens(clock.instant(), "1", Duration.ofHours(8)))
        clock.advance(Duration.ofDays(181))

        assertFailsWith<GitHubUserAuthenticationException> {
            store.resolve(issued.sessionToken)
        }
        assertEquals(0, oauth.refreshCount.get())
    }

    @Test
    fun `GitHub가 refresh token을 거부하면 session을 폐기한다`() {
        val issued = store.issue(owner, tokens(clock.instant(), "1", Duration.ofMinutes(4)))
        oauth.rejectRefresh = true

        assertFailsWith<GitHubUserAuthenticationException> {
            store.resolve(issued.sessionToken)
        }
        assertFailsWith<GitHubUserAuthenticationException> {
            store.resolve(issued.sessionToken)
        }
        assertEquals(1, oauth.refreshCount.get())
    }

    @Test
    fun `내 연결만 조회하고 폐기하며 다른 사용자의 연결은 유지한다`() {
        val first = store.issue(owner, tokens(clock.instant(), "1", Duration.ofHours(8)))
        store.issue(owner, tokens(clock.instant(), "other", Duration.ofHours(8)))
        val teammate = ActorIdentity.github(84, "teammate")
        store.issue(teammate, tokens(clock.instant(), "team", Duration.ofHours(8)))
        val id = store.resolve(first.sessionToken).sessionId!!
        assertEquals(2, store.list(owner.subject).size)
        assertFalse(store.list(owner.subject).toString().contains("ghu_"))
        assertFalse(store.revoke(teammate.subject, id))
        assertTrue(store.revoke(owner.subject, id))
        assertFailsWith<GitHubUserAuthenticationException> { store.resolve(first.sessionToken) }
        assertEquals(1, store.revokeAll(owner.subject))
        assertTrue(store.list(owner.subject).isEmpty())
        assertEquals(1, store.list(teammate.subject).size)
    }

    @Test
    fun `token 갱신 중 폐기한 session을 다시 활성화하지 않는다`() {
        val issued = store.issue(owner, tokens(clock.instant(), "1", Duration.ofMinutes(4)))
        val id = store.list(owner.subject).single().id
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        oauth.beforeRefresh = { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val resolving = executor.submit<GitHubUserSession> { store.resolve(issued.sessionToken) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(store.revoke(owner.subject, id))
            release.countDown()
            val error = assertFailsWith<ExecutionException> { resolving.get(5, TimeUnit.SECONDS) }
            assertTrue(error.cause is GitHubUserAuthenticationException)
            assertFailsWith<GitHubUserAuthenticationException> { store.resolve(issued.sessionToken) }
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test
    fun `브라우저 세션은 갱신해도 발급 후 8시간에 만료되고 클라이언트 세션은 유지된다`() {
        val browser = store.issue(owner, tokens(clock.instant(), "1", Duration.ofMinutes(4)), SessionChannel.BROWSER)
        val client = store.issue(owner, tokens(clock.instant(), "1", Duration.ofHours(8)))
        assertTrue(browser.sessionToken.startsWith("itb_"))
        store.resolve(browser.sessionToken)
        clock.advance(Duration.ofHours(8))
        assertFailsWith<GitHubUserAuthenticationException> { store.resolve(browser.sessionToken) }
        store.revokeBrowser(client.sessionToken)
        assertEquals(owner, store.resolve(client.sessionToken).actor)
    }

    private class FakeGitHubUserOAuthGateway(
        private val clock: Clock,
    ) : GitHubUserOAuthGateway {
        val refreshCount = AtomicInteger()
        var rejectRefresh = false
        var beforeRefresh: () -> Unit = {}

        override fun authorizationUri(state: String, codeChallenge: String): URI = error("사용하지 않는 테스트 경로")

        override fun exchange(code: String, codeVerifier: String): GitHubUserOAuthTokens =
            error("사용하지 않는 테스트 경로")

        override fun refresh(refreshToken: String): GitHubUserOAuthTokens {
            refreshCount.incrementAndGet()
            beforeRefresh()
            if (rejectRefresh) throw GitHubOAuthRefreshRejectedException()
            return tokens(clock.instant(), "2", Duration.ofHours(8))
        }
    }

    private class FakeGitHubUserAccessGateway : GitHubUserAccessGateway {
        var refreshedActor: ActorIdentity = owner

        override fun authenticate(accessToken: String): ActorIdentity =
            if (accessToken == "ghu_access-2") refreshedActor else owner

        override fun repositoryRole(accessToken: String, repository: GitHubRepository): RepositoryRole? =
            error("사용하지 않는 테스트 경로")
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    companion object {
        private val now = Instant.parse("2026-08-28T12:00:00Z")
        private val owner = ActorIdentity.github(42, "lim")
        private val properties = GitHubProperties(
            userAuthorization = GitHubUserAuthorizationProperties(
                refreshBeforeExpiry = Duration.ofMinutes(5),
            ),
        )

        private fun tokens(now: Instant, suffix: String, accessLifetime: Duration): GitHubUserOAuthTokens =
            GitHubUserOAuthTokens(
                accessToken = "ghu_access-$suffix",
                accessExpiresAt = now.plus(accessLifetime),
                refreshToken = "ghr_refresh-$suffix",
                refreshExpiresAt = now.plus(Duration.ofDays(180)),
            )
    }
}
