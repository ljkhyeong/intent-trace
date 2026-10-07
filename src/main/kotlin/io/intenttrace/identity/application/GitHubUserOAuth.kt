package io.intenttrace.identity.application

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.domain.ActorIdentity
import org.springframework.stereotype.Service
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
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

class GitHubOAuthStart(
    val state: String,
    val authorizationUri: URI,
) {
    override fun toString(): String = "GitHubOAuthStart(state=[보호됨], authorizationUri=[보호됨])"
}

class IssuedGitHubUserSession(val actor: ActorIdentity, val sessionToken: String) {
    override fun toString(): String = "IssuedGitHubUserSession(actor=$actor, sessionToken=[보호됨])"
}

enum class SessionChannel { CLIENT, BROWSER }

class GitHubOAuthCompletion(val session: IssuedGitHubUserSession, val returnTo: String?)

interface GitHubUserSessionStore {
    fun issue(actor: ActorIdentity, tokens: GitHubUserOAuthTokens, channel: SessionChannel = SessionChannel.CLIENT): IssuedGitHubUserSession

    fun resolve(sessionToken: String): GitHubUserSession

    fun revokeBrowser(sessionToken: String)
}

val OAUTH_STATE_TTL: Duration = Duration.ofMinutes(10)

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

    fun start(returnTo: String? = null): GitHubOAuthStart {
        returnTo?.let(BrowserReturnPath::validate)
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
                expiresAt = now.plus(OAUTH_STATE_TTL),
                codeVerifier = codeVerifier,
                returnTo = returnTo,
            )
        }
        return GitHubOAuthStart(state, authorizationUri)
    }

    fun complete(
        code: String?,
        state: String?,
        cookieState: String?,
        error: String?,
    ): GitHubOAuthCompletion {
        val verifiedState = verifyBrowserState(state, cookieState)
        val pending = pendingStates.remove(TokenDigests.sha256(verifiedState))
            ?: throw GitHubOAuthStateException()
        try {
            if (!Instant.now(clock).isBefore(pending.expiresAt)) throw GitHubOAuthStateException()
            if (!error.isNullOrBlank()) throw GitHubOAuthDeniedException()

            val verifiedCode = code?.takeIf {
                it.isNotBlank() && it.length <= MAX_CODE_LENGTH && it.none(Char::isWhitespace)
            } ?: throw GitHubOAuthCodeException()
            val tokens = oauthGateway.exchange(verifiedCode, pending.codeVerifier)
            val actor = userAccessGateway.authenticate(tokens.accessToken)
            return GitHubOAuthCompletion(
                sessions.issue(actor, tokens, if (pending.returnTo == null) SessionChannel.CLIENT else SessionChannel.BROWSER),
                pending.returnTo,
            )
        } catch (exception: RuntimeException) {
            if (pending.returnTo == null) throw exception
            throw GitHubOAuthCallbackException(pending.returnTo, exception)
        }
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
        val returnTo: String?,
    ) {
        override fun toString(): String = "PendingAuthorization(expiresAt=$expiresAt, codeVerifier=[보호됨])"
    }

    companion object {
        private const val MAX_CODE_LENGTH = 512
        private val STATE_TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    }
}

open class GitHubOAuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class GitHubOAuthCallbackException(val returnTo: String, cause: RuntimeException) :
    GitHubOAuthException("GitHub 브라우저 로그인을 완료하지 못했습니다.", cause)

class GitHubOAuthConfigurationException : GitHubOAuthException("GitHub 사용자 승인 설정을 사용할 수 없습니다.")

class GitHubOAuthStateException : GitHubOAuthException("GitHub 사용자 승인 state를 신뢰할 수 없습니다.")

class GitHubOAuthCodeException : GitHubOAuthException("GitHub 사용자 승인 code가 올바르지 않습니다.")

class GitHubOAuthDeniedException : GitHubOAuthException("GitHub 사용자 승인이 취소됐습니다.")

class GitHubOAuthCapacityException : GitHubOAuthException("GitHub 사용자 승인 대기 요청이 너무 많습니다.")

class GitHubOAuthApiException(message: String) : GitHubOAuthException(message)

private object Pkce {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun challenge(codeVerifier: String): String = MessageDigest.getInstance("SHA-256")
        .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        .let(encoder::encodeToString)
}

private fun String.isSafeToken(prefix: String): Boolean =
    startsWith(prefix) && length <= 8_192 && none(Char::isWhitespace)
