package io.intenttrace.identity.application

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.domain.ActorIdentity
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class InMemoryGitHubUserSessionStore(
    private val oauthGateway: GitHubUserOAuthGateway,
    private val userAccessGateway: GitHubUserAccessGateway,
    private val properties: GitHubProperties,
    private val clock: Clock,
) : GitHubUserSessionStore, UserSessionManagement {
    private val sessions = ConcurrentHashMap<String, StoredSession>()
    private val issuanceLock = ReentrantLock()

    override fun issue(actor: ActorIdentity, tokens: GitHubUserOAuthTokens, channel: SessionChannel): IssuedGitHubUserSession = issuanceLock.withLock {
        val now = Instant.now(clock)
        require(now.isBefore(tokens.accessExpiresAt)) { "이미 만료된 GitHub token은 session에 넣을 수 없습니다." }
        removeExpiredSessions(now)
        removeOldestSessionsAtLimit(actor, channel)
        val prefix = if (channel == SessionChannel.BROWSER) "itb_" else "its_"
        val sessionToken = "$prefix${SecureTokens.random()}"
        val expiresAt = if (channel == SessionChannel.BROWSER) minOf(now.plus(BROWSER_SESSION_TTL), tokens.refreshExpiresAt) else tokens.refreshExpiresAt
        sessions[TokenDigests.sha256(sessionToken)] = StoredSession(actor, tokens, UUID.randomUUID(), now, channel, expiresAt)
        IssuedGitHubUserSession(actor, sessionToken)
    }

    override fun resolve(sessionToken: String): GitHubUserSession {
        val key = TokenDigests.sha256(sessionToken)
        val stored = sessions[key] ?: throw GitHubUserAuthenticationException()
        return stored.lock.withLock {
            val now = Instant.now(clock)
            if (!stored.active.get() || sessions[key] !== stored || stored.isExpired(now)) {
                revoke(key, stored)
                throw GitHubUserAuthenticationException()
            }
            if (!now.isBefore(stored.tokens.accessExpiresAt.minus(USER_TOKEN_REFRESH_MARGIN))) {
                stored.tokens = try {
                    oauthGateway.refresh(stored.tokens.refreshToken)
                } catch (_: GitHubOAuthException) {
                    sessions.remove(key, stored)
                    throw GitHubUserAuthenticationException()
                }
            }

            val verifiedActor = try {
                userAccessGateway.authenticate(stored.tokens.accessToken)
            } catch (exception: GitHubUserAuthenticationException) {
                sessions.remove(key, stored)
                throw exception
            }
            if (verifiedActor.subject != stored.actor.subject) {
                sessions.remove(key, stored)
                throw GitHubUserAuthenticationException()
            }
            val authenticatedAt = Instant.now(clock)
            if (!stored.active.get() || sessions[key] !== stored || stored.isExpired(authenticatedAt)) {
                revoke(key, stored)
                throw GitHubUserAuthenticationException()
            }
            stored.actor = verifiedActor
            stored.lastUsedAt = authenticatedAt
            GitHubUserSession(verifiedActor, stored.tokens.accessToken, stored.id)
        }
    }

    private fun removeExpiredSessions(now: Instant) {
        sessions.forEach { (key, stored) ->
            // 사용 중인 세션 정리는 다음 발급으로 미뤄 다른 로그인을 막지 않는다.
            if (!stored.lock.tryLock()) return@forEach
            try {
                if (stored.isExpired(now)) {
                    revoke(key, stored)
                }
            } finally {
                stored.lock.unlock()
            }
        }
    }

    private fun removeOldestSessionsAtLimit(actor: ActorIdentity, channel: SessionChannel) {
        // 같은 종류의 오래된 연결부터 정리해 브라우저 로그인이 도구 연결을 끊지 않게 한다.
        val activeSessions = sessions.entries
            .filter { it.value.actor.subject == actor.subject }
            .sortedWith(compareBy({ it.value.channel != channel }, { it.value.createdAt }))
        val removalCount = activeSessions.size - properties.userAuthorization.maxSessionsPerUser + 1
        activeSessions.take(removalCount.coerceAtLeast(0)).forEach { (key, stored) ->
            revoke(key, stored)
        }
    }

    override fun list(subject: String): List<UserSessionInfo> {
        val now = Instant.now(clock)
        return sessions.values.filter { it.actor.subject == subject && it.active.get() && !it.isExpired(now) }
            .map { stored ->
                val tokens = stored.tokens
                UserSessionInfo(stored.id, stored.createdAt, stored.lastUsedAt, tokens.accessExpiresAt, tokens.refreshExpiresAt,
                    channel = stored.channel, expiresAt = stored.expiresAt)
            }.sortedByDescending { it.createdAt }
    }

    override fun revokeBrowser(sessionToken: String) {
        if (!sessionToken.startsWith("itb_")) return
        val key = TokenDigests.sha256(sessionToken)
        val stored = sessions[key] ?: return
        if (stored.channel != SessionChannel.BROWSER) return
        revoke(key, stored)
    }

    override fun revoke(subject: String, sessionId: UUID): Boolean {
        val entry = sessions.entries.firstOrNull { it.value.id == sessionId && it.value.actor.subject == subject } ?: return false
        return revoke(entry.key, entry.value)
    }

    override fun revokeAll(subject: String): Int =
        sessions.entries.count { (key, stored) -> stored.actor.subject == subject && revoke(key, stored) }

    private fun revoke(key: String, stored: StoredSession): Boolean {
        val revoked = stored.active.compareAndSet(true, false)
        sessions.remove(key, stored)
        return revoked
    }

    private class StoredSession(
        @Volatile var actor: ActorIdentity,
        @Volatile var tokens: GitHubUserOAuthTokens,
        val id: UUID,
        val createdAt: Instant,
        val channel: SessionChannel,
        private val issuedExpiresAt: Instant,
    ) {
        val expiresAt: Instant get() = if (channel == SessionChannel.BROWSER) issuedExpiresAt else tokens.refreshExpiresAt
        val lock = ReentrantLock()
        val active = AtomicBoolean(true)
        @Volatile var lastUsedAt: Instant = createdAt

        fun isExpired(at: Instant): Boolean = !at.isBefore(expiresAt) || !at.isBefore(tokens.refreshExpiresAt)

        override fun toString(): String = "StoredSession(actor=$actor, tokens=[보호됨])"
    }
}

val BROWSER_SESSION_TTL: Duration = Duration.ofHours(8)
private val USER_TOKEN_REFRESH_MARGIN: Duration = Duration.ofMinutes(5)
