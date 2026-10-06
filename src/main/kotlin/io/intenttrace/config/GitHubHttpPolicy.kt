package io.intenttrace.config

import org.springframework.boot.restclient.RestClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant

// API 버전은 응답 해석 코드와 함께 바꾼다.
private const val GITHUB_API_VERSION = "2026-03-10"

class GitHubRateLimitException(val retryAfterSeconds: Long) : RuntimeException("GitHub 호출 제한에 도달했습니다. ${retryAfterSeconds}초 후 다시 시도하세요.")

object GitHubRateLimit {
    fun detect(status: Int, headers: HttpHeaders, now: Instant): GitHubRateLimitException? {
        val retry = headers.getFirst(HttpHeaders.RETRY_AFTER)
        val exhausted = headers.getFirst("X-RateLimit-Remaining") == "0"
        if (status != 429 && !(status == 403 && (retry != null || exhausted))) return null
        val retrySeconds = retry?.toLongOrNull() ?: runCatching {
            headers.getFirstZonedDateTime(HttpHeaders.RETRY_AFTER)?.let { Duration.between(now, it.toInstant()).seconds }
        }.getOrNull()
        val reset = if (exhausted) headers.getFirst("X-RateLimit-Reset")?.toLongOrNull()?.let { it - now.epochSecond } else null
        return GitHubRateLimitException(maxOf(retrySeconds ?: 0, reset ?: 0, 1).takeIf { retrySeconds != null || reset != null } ?: 60)
    }
}

/** RestClient는 응답 크기를 제한하지 않으므로 [limit]바이트까지만 읽고 JSON으로 해석한다. 오류 메시지에 응답 원문을 넣지 않는다. */
fun <T> ClientHttpResponse.readJsonWithin(mapper: ObjectMapper, type: Class<T>, limit: Int, tooLarge: () -> Nothing): T {
    val bytes = body.readNBytes(limit + 1)
    if (bytes.size > limit) tooLarge()
    return try { mapper.readValue(bytes, type) } catch (_: RuntimeException) {
        throw GitHubApiException("GitHub 응답 형식을 해석할 수 없습니다.")
    }
}

@Configuration
class GitHubHttpPolicy {
    @Bean
    fun githubApiRestClient(builder: RestClient.Builder, properties: GitHubProperties): RestClient = builder
        .baseUrl(properties.apiBaseUrl.toString().trimEnd('/'))
        .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
        .defaultHeader("X-GitHub-Api-Version", GITHUB_API_VERSION)
        .build()

    // 호출 지표는 Spring 기본 관측(http.client.requests)이 URI 템플릿 단위로 기록한다. 여기서는 호출 제한만 감지한다.
    @Bean
    fun githubRequestPolicy(properties: GitHubProperties, clock: Clock): RestClientCustomizer = RestClientCustomizer { builder ->
        builder.requestInterceptor { request, body, execution ->
            execution.execute(request, body).also { response ->
                if (request.uri.host in setOf(properties.apiBaseUrl.host, properties.userAuthorization.webBaseUrl.host)) {
                    GitHubRateLimit.detect(response.statusCode.value(), response.headers, Instant.now(clock))?.let {
                        response.close()
                        throw it
                    }
                }
            }
        }
    }
}
