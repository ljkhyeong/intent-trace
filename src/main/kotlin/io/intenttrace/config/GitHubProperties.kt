package io.intenttrace.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException

const val GITHUB_OAUTH_CALLBACK_PATH = "/auth/github/callback"

@ConfigurationProperties("intent-trace.github")
data class GitHubProperties(
    val apiBaseUrl: URI = URI.create("https://api.github.com"),
    val apiVersion: String = "2026-03-10",
    val token: String = "",
    val webhookSecret: String = "",
    val app: GitHubAppProperties = GitHubAppProperties(),
    val userAuthorization: GitHubUserAuthorizationProperties = GitHubUserAuthorizationProperties(),
) {
    init {
        requireHttpsBaseUrl(apiBaseUrl, "API")
        val validVersion = apiVersion.length == 10 &&
            try { LocalDate.parse(apiVersion); true } catch (_: DateTimeParseException) { false }
        require(validVersion) { "GitHub API 버전은 유효한 YYYY-MM-DD 날짜여야 합니다." }
    }

    override fun toString(): String =
        "GitHubProperties(apiBaseUrl=$apiBaseUrl, apiVersion=$apiVersion, token=[보호됨], webhookSecret=[보호됨], " +
            "app=$app, userAuthorization=$userAuthorization)"
}

data class GitHubAppProperties(
    val clientId: String = "",
    val privateKeyBase64: String = "",
    val refreshBeforeExpiry: Duration = Duration.ofMinutes(5),
) {
    init {
        require(refreshBeforeExpiry.isPositive) {
            "GitHub App token 갱신 여유 시간은 0보다 커야 합니다."
        }
        require(refreshBeforeExpiry <= Duration.ofMinutes(30)) {
            "GitHub App token 갱신 여유 시간은 30분 이하여야 합니다."
        }
    }

    override fun toString(): String =
        "GitHubAppProperties(clientId=$clientId, privateKeyBase64=[보호됨], " +
            "refreshBeforeExpiry=$refreshBeforeExpiry)"
}

data class GitHubUserAuthorizationProperties(
    val webBaseUrl: URI = URI.create("https://github.com"),
    val clientSecret: String = "",
    val callbackUrl: URI = URI.create("http://127.0.0.1:8080/auth/github/callback"),
    val stateTtl: Duration = Duration.ofMinutes(10),
    val maxPendingStates: Int = 1_000,
    val maxSessionsPerUser: Int = 5,
    val refreshBeforeExpiry: Duration = Duration.ofMinutes(5),
) {
    init {
        requireHttpsBaseUrl(webBaseUrl, "웹")
        require(webBaseUrl.path.isNullOrBlank() || webBaseUrl.path == "/") {
            "GitHub 웹 기본 주소에는 별도 경로를 넣을 수 없습니다."
        }
        require(callbackUrl.isSafeCallback()) {
            "GitHub callback URL은 정해진 경로를 사용하는 HTTPS 또는 loopback HTTP 주소여야 합니다."
        }
        require(stateTtl.isPositive && stateTtl <= Duration.ofMinutes(30)) {
            "GitHub OAuth state 유효 시간은 0보다 크고 30분 이하여야 합니다."
        }
        require(maxPendingStates in 1..100_000) {
            "GitHub OAuth 대기 state 상한은 1 이상 100,000 이하여야 합니다."
        }
        require(maxSessionsPerUser in 1..100) {
            "GitHub 사용자별 session 상한은 1 이상 100 이하여야 합니다."
        }
        require(
            refreshBeforeExpiry.isPositive &&
                refreshBeforeExpiry <= Duration.ofMinutes(30),
        ) {
            "GitHub 사용자 token 갱신 여유 시간은 0보다 크고 30분 이하여야 합니다."
        }
    }

    val secureCookie: Boolean = callbackUrl.scheme.equals("https", ignoreCase = true)

    override fun toString(): String =
        "GitHubUserAuthorizationProperties(webBaseUrl=$webBaseUrl, clientSecret=[보호됨], " +
            "callbackUrl=$callbackUrl, stateTtl=$stateTtl, maxPendingStates=$maxPendingStates, " +
            "maxSessionsPerUser=$maxSessionsPerUser, " +
            "refreshBeforeExpiry=$refreshBeforeExpiry, " +
            "secureCookie=$secureCookie)"

    private fun URI.isSafeCallback(): Boolean {
        if (host.isNullOrBlank() || userInfo != null || query != null || fragment != null || path != GITHUB_OAUTH_CALLBACK_PATH) {
            return false
        }
        if (scheme.equals("https", ignoreCase = true)) return true
        if (!scheme.equals("http", ignoreCase = true)) return false
        return host.lowercase().removeSurrounding("[", "]") in LOOPBACK_HOSTS
    }

    companion object {
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
    }
}

private fun requireHttpsBaseUrl(url: URI, label: String) {
    require(url.scheme == "https" && !url.host.isNullOrBlank()) { "GitHub $label 기본 주소는 유효한 HTTPS 주소여야 합니다." }
    require(url.userInfo == null && url.query == null && url.fragment == null) {
        "GitHub $label 기본 주소에는 사용자 정보, 쿼리 또는 fragment를 넣을 수 없습니다."
    }
}
