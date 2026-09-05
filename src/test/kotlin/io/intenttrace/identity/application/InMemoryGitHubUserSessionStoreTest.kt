package io.intenttrace.identity.application

import io.intenttrace.config.GitHubProperties
import io.intenttrace.config.GitHubUserAuthorizationProperties
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
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
import java.util.concurrent.FutureTask
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
    fun `session을 폐기하면 해당 token만 다시 사용할 수 없다`() {
        val first = store.issue(owner, tokens(clock.instant(), "1", Duration.ofHours(8)))
        val second = store.issue(owner, tokens(clock.instant(), "2", Duration.ofHours(8)))

        store.revoke(store.resolve(first.sessionToken).localSessionId!!)

        assertFailsWith<GitHubUserAuthenticationException> { store.resolve(first.sessionToken) }
        assertEquals("ghu_access-2", store.resolve(second.sessionToken).accessToken)
    }

    @Test
    fun `사용자별 상한을 넘겨 발급하면 가장 오래된 session을 폐기한다`() {
        val limitedStore = InMemoryGitHubUserSessionStore(
            oauth,
            users,
            GitHubProperties(userAuthorization = GitHubUserAuthorizationProperties(maxSessionsPerUser = 2)),
            clock,
        )
        val first = limitedStore.issue(owner, tokens(clock.instant(), "1", Duration.ofHours(8)))
        clock.advance(Duration.ofSeconds(1))
        val second = limitedStore.issue(owner, tokens(clock.instant(), "2", Duration.ofHours(8)))
        clock.advance(Duration.ofSeconds(1))
        val third = limitedStore.issue(owner, tokens(clock.instant(), "3", Duration.ofHours(8)))

        assertFailsWith<GitHubUserAuthenticationException> { limitedStore.resolve(first.sessionToken) }
        assertEquals("ghu_access-2", limitedStore.resolve(second.sessionToken).accessToken)
        assertEquals("ghu_access-3", limitedStore.resolve(third.sessionToken).accessToken)
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
        oauth.refreshFailure = GitHubOAuthRefreshRejectedException()

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

    @Test
    fun `갱신 응답을 받지 못하면 session을 폐기하고 같은 refresh token을 다시 보내지 않는다`() {
        val issued = store.issue(owner, tokens(clock.instant(), "1", Duration.ofMinutes(4)))
        oauth.refreshFailure = GitHubOAuthApiException("GitHub 사용자 token 갱신 요청을 완료하지 못했습니다.")

        repeat(2) {
            assertFailsWith<GitHubUserAuthenticationException> {
                store.resolve(issued.sessionToken)
            }
        }
        assertEquals(1, oauth.refreshCount.get())
    }

    @Test
    fun `사용자 조회 장애는 갱신한 token 쌍을 폐기하지 않는다`() {
        val issued = store.issue(owner, tokens(clock.instant(), "1", Duration.ofMinutes(4)))
        users.authenticationFailure = GitHubIdentityApiException("테스트 사용자 조회 장애")

        assertFailsWith<GitHubIdentityApiException> { store.resolve(issued.sessionToken) }
        users.authenticationFailure = null

        assertEquals("ghu_access-2", store.resolve(issued.sessionToken).accessToken)
        assertEquals(1, oauth.refreshCount.get())
    }

    @Test
    fun `갱신 거부로 폐기한 session은 잠금을 기다리던 요청도 사용하지 않는다`() {
        val issued = store.issue(owner, tokens(clock.instant(), "1", Duration.ofMinutes(4)))
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        oauth.refreshFailure = GitHubOAuthRefreshRejectedException()
        oauth.beforeRefresh = {
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
        }
        val first = FutureTask {
            assertFailsWith<GitHubUserAuthenticationException> { store.resolve(issued.sessionToken) }
        }
        val second = FutureTask {
            assertFailsWith<GitHubUserAuthenticationException> { store.resolve(issued.sessionToken) }
        }
        val firstThread = Thread(first)
        val secondThread = Thread(second)
        try {
            firstThread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            secondThread.start()
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                while (secondThread.state != Thread.State.WAITING) Thread.sleep(1)
            }
        } finally {
            proceed.countDown()
            firstThread.join(5_000)
            secondThread.join(5_000)
        }

        first.get(5, TimeUnit.SECONDS)
        second.get(5, TimeUnit.SECONDS)
        assertEquals(1, oauth.refreshCount.get())
    }

    private class FakeGitHubUserOAuthGateway(
        private val clock: Clock,
    ) : GitHubUserOAuthGateway {
        val refreshCount = AtomicInteger()
        var rejectRefresh = false
        var refreshFailure: GitHubOAuthException? = null
        var beforeRefresh: () -> Unit = {}

        override fun authorizationUri(state: String, codeChallenge: String): URI = error("사용하지 않는 테스트 경로")

        override fun exchange(code: String, codeVerifier: String): GitHubUserOAuthTokens =
            error("사용하지 않는 테스트 경로")

        override fun refresh(refreshToken: String): GitHubUserOAuthTokens {
            refreshCount.incrementAndGet()
            beforeRefresh()
            if (rejectRefresh) throw GitHubOAuthRefreshRejectedException()
            refreshFailure?.let { throw it }
            return tokens(clock.instant(), "2", Duration.ofHours(8))
        }
    }

    private class FakeGitHubUserAccessGateway : GitHubUserAccessGateway {
        var refreshedActor: ActorIdentity = owner
        var authenticationFailure: GitHubIdentityApiException? = null

        override fun authenticate(accessToken: String): ActorIdentity {
            authenticationFailure?.let { throw it }
            return if (accessToken == "ghu_access-2") refreshedActor else owner
        }

        override fun repositoryRole(
            accessToken: String,
            actor: ActorIdentity,
            repository: GitHubRepository,
        ): RepositoryRole? =
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
