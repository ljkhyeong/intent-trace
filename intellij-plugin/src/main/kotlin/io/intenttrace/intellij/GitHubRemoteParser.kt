package io.intenttrace.intellij

import java.net.URI

internal object GitHubRemoteParser {
    private val SCP_STYLE = Regex("^(?:[^@/]+@)?github\\.com[:/]([^/]+)/([^/]+?)(?:\\.git)?/?$", RegexOption.IGNORE_CASE)

    fun repositoryKey(remote: String): String? {
        val value = remote.trim()
        SCP_STYLE.matchEntire(value)?.let { match ->
            return normalize(match.groupValues[1], match.groupValues[2])
        }

        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.host.equals("github.com", ignoreCase = true)) return null
        val segments = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
        if (segments.size != 2) return null
        return normalize(segments[0], segments[1])
    }

    private fun normalize(owner: String, repository: String): String? {
        val normalizedOwner = owner.lowercase()
        val normalizedRepository = repository.lowercase().removeSuffix(".git")
        val validPart = Regex("^[a-z0-9_.-]+$")
        if (!validPart.matches(normalizedOwner) || !validPart.matches(normalizedRepository)) return null
        return "$normalizedOwner/$normalizedRepository"
    }
}
