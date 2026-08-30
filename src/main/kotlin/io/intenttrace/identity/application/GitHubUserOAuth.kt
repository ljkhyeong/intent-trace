package io.intenttrace.identity.application

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.domain.ActorIdentity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class GitHubUserOAuthTokens(
    val accessToken: String,
    val accessExpiresAt: Instant,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
) {
    init {
        require(accessToken.isSafeToken("ghu_")) { "GitHub user access token 형식이 올바르지 않습니다." }
        require(refreshToken.isSafeToken("ghr_")) { "GitHub refresh token 형식이 올바르지 않습니다." }
        require(accessExpiresAt.isBefore(refreshExpiresAt)) { "GitHub refresh token은 access token보다 늦게 만료되어야 합니다." }
    }

    override fun toString(): String =
        "GitHubUserOAuthTokens(accessToken=[보호됨], accessExpiresAt=$accessExpiresAt, " +
            "refreshToken=[보호됨], refreshExpiresAt=$refreshExpiresAt)"
}

interface GitHubUserOAuthGateway {
    fun authorizationUri(state: String, codeChallenge: String): URI

    fun exchange(code: String, codeVerifier: String): GitHubUserOAuthTokens

    fun refresh(refreshToken: String): GitHubUserOAuthTokens
}

interface GitHubUserCredentialProvider {
    fun authenticate(bearerToken: String): GitHubUserSession
}

class GitHubOAuthStart(
    val state: String,
    val authorizationUri: URI,
) {
    override fun toString(): String = "GitHubOAuthStart(state=[보호됨], authorizationUri=[보호됨])"
}

class IssuedGitHubUserSession(
    val actor: ActorIdentity,
    val sessionToken: String,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant,
) {
    override fun toString(): String =
        "IssuedGitHubUserSession(actor=$actor, sessionToken=[보호됨], " +
            "accessExpiresAt=$accessExpiresAt, refreshExpiresAt=$refreshExpiresAt)"
}

interface GitHubUserSessionStore {
    fun issue(actor: ActorIdentity, tokens: GitHubUserOAuthTokens): IssuedGitHubUserSession

    fun resolve(sessionToken: String): GitHubUserSession
}

@Service
class GitHubOAuthFlowService(
    private val oauthGateway: GitHubUserOAuthGateway,
    private val userAccessGateway: GitHubUserAccessGateway,
    private val sessions: GitHubUserSessionStore,
    private val properties: GitHubProperties,
    private val clock: Clock,
) {
    private val pendingStates = ConcurrentHashMap<String, PendingAuthorization>()
    private val pendingStateLock = ReentrantLock()

    fun start(): GitHubOAuthStart {
        val state = SecureTokens.random()
        val codeVerifier = SecureTokens.random()
        val authorizationUri = oauthGateway.authorizationUri(state, Pkce.challenge(codeVerifier))
        pendingStateLock.withLock {
            val now = Instant.now(clock)
            removeExpiredStates(now)
            if (pendingStates.size >= properties.userAuthorization.maxPendingStates) {
                throw GitHubOAuthCapacityException()
            }
            pendingStates[TokenDigests.sha256(state)] = PendingAuthorization(
                expiresAt = now.plus(properties.userAuthorization.stateTtl),
                codeVerifier = codeVerifier,
            )
        }
        return GitHubOAuthStart(state, authorizationUri)
    }

    fun complete(
        code: String?,
        state: String?,
        cookieState: String?,
        error: String?,
    ): IssuedGitHubUserSession {
        val verifiedState = verifyBrowserState(state, cookieState)
        val pending = pendingStates.remove(TokenDigests.sha256(verifiedState))
            ?: throw GitHubOAuthStateException()
        if (!Instant.now(clock).isBefore(pending.expiresAt)) throw GitHubOAuthStateException()
        if (!error.isNullOrBlank()) throw GitHubOAuthDeniedException()

        val verifiedCode = code?.takeIf {
            it.isNotBlank() && it.length <= MAX_CODE_LENGTH && it.none(Char::isWhitespace)
        } ?: throw GitHubOAuthCodeException()
        val tokens = oauthGateway.exchange(verifiedCode, pending.codeVerifier)
        val actor = userAccessGateway.authenticate(tokens.accessToken)
        return sessions.issue(actor, tokens)
    }

    private fun verifyBrowserState(state: String?, cookieState: String?): String {
        val left = state?.takeIf(STATE_TOKEN::matches) ?: throw GitHubOAuthStateException()
        val right = cookieState?.takeIf(STATE_TOKEN::matches) ?: throw GitHubOAuthStateException()
        if (!MessageDigest.isEqual(left.toByteArray(Charsets.US_ASCII), right.toByteArray(Charsets.US_ASCII))) {
            throw GitHubOAuthStateException()
        }
        return left
    }

    private fun removeExpiredStates(now: Instant) {
        pendingStates.entries.removeIf { !now.isBefore(it.value.expiresAt) }
    }

    private data class PendingAuthorization(
        val expiresAt: Instant,
        val codeVerifier: String,
    ) {
        override fun toString(): String = "PendingAuthorization(expiresAt=$expiresAt, codeVerifier=[보호됨])"
    }

    companion object {
        private const val MAX_CODE_LENGTH = 512
        private val STATE_TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    }
}

@Service
class GitHubUserCredentialService(
    private val userAccessGateway: GitHubUserAccessGateway,
    private val sessions: GitHubUserSessionStore,
) : GitHubUserCredentialProvider {
    override fun authenticate(bearerToken: String): GitHubUserSession = when {
        bearerToken.startsWith("ghu_") -> GitHubUserSession(
            actor = userAccessGateway.authenticate(bearerToken),
            accessToken = bearerToken,
        )
        bearerToken.startsWith("its_") -> sessions.resolve(bearerToken)
        else -> throw GitHubUserAuthenticationException()
    }
}

@Component
class InMemoryGitHubUserSessionStore(
    private val oauthGateway: GitHubUserOAuthGateway,
    private val userAccessGateway: GitHubUserAccessGateway,
    private val properties: GitHubProperties,
    private val clock: Clock,
) : GitHubUserSessionStore {
    private val sessions = ConcurrentHashMap<String, StoredSession>()

    override fun issue(actor: ActorIdentity, tokens: GitHubUserOAuthTokens): IssuedGitHubUserSession {
        val now = Instant.now(clock)
        require(now.isBefore(tokens.accessExpiresAt)) { "이미 만료된 GitHub token은 session에 넣을 수 없습니다." }
        sessions.entries.removeIf { !now.isBefore(it.value.tokens.refreshExpiresAt) }
        val sessionToken = "its_${SecureTokens.random()}"
        sessions[TokenDigests.sha256(sessionToken)] = StoredSession(actor, tokens)
        return IssuedGitHubUserSession(
            actor = actor,
            sessionToken = sessionToken,
            accessExpiresAt = tokens.accessExpiresAt,
            refreshExpiresAt = tokens.refreshExpiresAt,
        )
    }

    override fun resolve(sessionToken: String): GitHubUserSession {
        val key = TokenDigests.sha256(sessionToken)
        val stored = sessions[key] ?: throw GitHubUserAuthenticationException()
        return stored.lock.withLock {
            if (sessions[key] !== stored) throw GitHubUserAuthenticationException()
            val now = Instant.now(clock)
            if (!now.isBefore(stored.tokens.refreshExpiresAt)) {
                sessions.remove(key, stored)
                throw GitHubUserAuthenticationException()
            }
            if (!now.isBefore(stored.tokens.accessExpiresAt.minus(properties.userAuthorization.refreshBeforeExpiry))) {
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
            stored.actor = verifiedActor
            GitHubUserSession(verifiedActor, stored.tokens.accessToken)
        }
    }

    private class StoredSession(
        var actor: ActorIdentity,
        @Volatile var tokens: GitHubUserOAuthTokens,
    ) {
        val lock = ReentrantLock()

        override fun toString(): String = "StoredSession(actor=$actor, tokens=[보호됨])"
    }
}

open class GitHubOAuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class GitHubOAuthConfigurationException : GitHubOAuthException("GitHub 사용자 승인 설정을 사용할 수 없습니다.")

class GitHubOAuthStateException : GitHubOAuthException("GitHub 사용자 승인 state를 신뢰할 수 없습니다.")

class GitHubOAuthCodeException : GitHubOAuthException("GitHub 사용자 승인 code가 올바르지 않습니다.")

class GitHubOAuthDeniedException : GitHubOAuthException("GitHub 사용자 승인이 취소됐습니다.")

class GitHubOAuthCapacityException : GitHubOAuthException("GitHub 사용자 승인 대기 요청이 너무 많습니다.")

class GitHubOAuthApiException(message: String, cause: Throwable? = null) : GitHubOAuthException(message, cause)

class GitHubOAuthRefreshRejectedException : GitHubOAuthException("GitHub refresh token이 거부됐습니다.")

private object SecureTokens {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun random(): String = ByteArray(32).also(random::nextBytes).let(encoder::encodeToString)
}

private object TokenDigests {
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.US_ASCII))
        .let(HexFormat.of()::formatHex)
}

private object Pkce {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun challenge(codeVerifier: String): String = MessageDigest.getInstance("SHA-256")
        .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        .let(encoder::encodeToString)
}

private fun String.isSafeToken(prefix: String): Boolean =
    startsWith(prefix) && length <= 8_192 && none(Char::isWhitespace)
