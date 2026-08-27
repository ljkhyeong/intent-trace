package io.intenttrace.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

@ConfigurationProperties("intent-trace.github")
data class GitHubProperties(
    val apiBaseUrl: URI = URI.create("https://api.github.com"),
    val apiVersion: String = "2026-03-10",
    val token: String = "",
) {
    init {
        require(apiBaseUrl.scheme == "https" && !apiBaseUrl.host.isNullOrBlank()) {
            "GitHub API 기본 주소는 유효한 HTTPS 주소여야 합니다."
        }
        require(apiBaseUrl.userInfo == null && apiBaseUrl.query == null && apiBaseUrl.fragment == null) {
            "GitHub API 기본 주소에는 사용자 정보, 쿼리 또는 fragment를 넣을 수 없습니다."
        }
        require(API_VERSION.matches(apiVersion)) { "GitHub API 버전은 YYYY-MM-DD 형식이어야 합니다." }
    }

    companion object {
        private val API_VERSION = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    }
}
