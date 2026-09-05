package io.intenttrace.config

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.restclient.RestClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class GitHubRateLimitException(val retryAfterSeconds: Long) : RuntimeException("GitHub 호출 제한에 도달했습니다. ${retryAfterSeconds}초 후 다시 시도하세요.")

object GitHubRateLimit {
    fun detect(status: Int, headers: HttpHeaders, now: Instant): GitHubRateLimitException? {
        val retry = headers.getFirst(HttpHeaders.RETRY_AFTER)
        val exhausted = headers.getFirst("X-RateLimit-Remaining") == "0"
        if (status != 429 && !(status == 403 && (retry != null || exhausted))) return null
        val retrySeconds = retry?.toLongOrNull() ?: retry?.let {
            runCatching { Duration.between(now, ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).seconds }.getOrNull()
        }
        val reset = if (exhausted) headers.getFirst("X-RateLimit-Reset")?.toLongOrNull()?.let { it - now.epochSecond } else null
        return GitHubRateLimitException(maxOf(retrySeconds ?: 0, reset ?: 0, 1).takeIf { retrySeconds != null || reset != null } ?: 60)
    }
}

@Configuration
class GitHubHttpPolicy {
    @Bean
    fun githubRequestPolicy(properties: GitHubProperties, clock: Clock, meters: MeterRegistry): RestClientCustomizer = RestClientCustomizer { builder ->
        builder.requestInterceptor { request, body, execution ->
            if (request.uri.host !in setOf(properties.apiBaseUrl.host, properties.userAuthorization.webBaseUrl.host)) {
                return@requestInterceptor execution.execute(request, body)
            }
            val operation = when {
                request.uri.path == "/user" -> "user"
                request.uri.path == "/user/repos" -> "repository_access"
                request.uri.path.contains("/check-runs") -> "check_run"
                request.uri.path.contains("/pulls/") -> "pull_request"
                request.uri.path.contains("/git/") || request.uri.path.contains("/compare/") -> "code_evidence"
                request.uri.path.contains("/login/oauth/") -> "user_token"
                else -> "installation"
            }
            val started = System.nanoTime()
            var outcome = "network_error"
            try {
                val response = execution.execute(request, body)
                outcome = "${response.statusCode.value() / 100}xx"
                GitHubRateLimit.detect(response.statusCode.value(), response.headers, Instant.now(clock))?.let {
                    response.close()
                    outcome = "rate_limited"
                    throw it
                }
                response
            } finally {
                meters.timer("intenttrace.github.request", "operation", operation, "outcome", outcome)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
            }
        }
    }
}
