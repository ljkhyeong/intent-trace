package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubProperties
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

interface GitHubAccessTokenProvider {
    fun token(target: GitHubPullRequestTarget): String

    fun invalidate(target: GitHubPullRequestTarget, rejectedToken: String): Boolean
}

@Component
class CachingGitHubAccessTokenProvider(
    private val properties: GitHubProperties,
    private val tokenIssuer: GitHubInstallationTokenIssuer,
    private val clock: Clock,
) : GitHubAccessTokenProvider {
    private val tokens = ConcurrentHashMap<String, GitHubInstallationAccessToken>()
    private val locks = ConcurrentHashMap<String, Any>()

    override fun token(target: GitHubPullRequestTarget): String {
        properties.token.trim().takeIf { it.isNotEmpty() }?.let { return it }

        val key = target.repositoryKey.lowercase(Locale.ROOT)
        tokens[key]?.takeIf(::isUsable)?.let { return it.value }
        return synchronized(locks.computeIfAbsent(key) { Any() }) {
            tokens[key]?.takeIf(::isUsable)?.value
                ?: tokenIssuer.issue(target).also { tokens[key] = it }.value
        }
    }

    override fun invalidate(target: GitHubPullRequestTarget, rejectedToken: String): Boolean {
        if (properties.token.isNotBlank()) {
            return false
        }
        val key = target.repositoryKey.lowercase(Locale.ROOT)
        tokens.computeIfPresent(key) { _, current -> current.takeUnless { it.value == rejectedToken } }
        return true
    }

    private fun isUsable(token: GitHubInstallationAccessToken): Boolean =
        Instant.now(clock).plus(properties.app.refreshBeforeExpiry).isBefore(token.expiresAt)
}
