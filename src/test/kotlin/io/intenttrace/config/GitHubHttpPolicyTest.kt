package io.intenttrace.config

import io.intenttrace.identity.adapter.out.github.GitHubUserRestClient
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GitHubHttpPolicyTest {
    @ParameterizedTest
    @EnumSource(HttpStatus::class, names = ["OK", "UNAUTHORIZED", "NOT_FOUND", "FORBIDDEN", "TOO_MANY_REQUESTS", "INTERNAL_SERVER_ERROR"])
    fun `저장소 권한 확인의 정상 응답과 실패를 권한 조회 지표로 집계한다`(status: HttpStatus) {
        val properties = GitHubProperties(apiBaseUrl = URI("https://api.github.test"))
        val meters = SimpleMeterRegistry()
        val builder = RestClient.builder()
        val policy = GitHubHttpPolicy()
        policy.githubRequestPolicy(properties, Clock.fixed(now, ZoneOffset.UTC), meters).customize(builder)
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = GitHubUserRestClient(policy.githubApiRestClient(builder, properties))
        val response = withStatus(status)
        if (status == HttpStatus.OK) {
            response.contentType(MediaType.APPLICATION_JSON)
                .body("""{"permission":"read","user":{"id":42,"login":"private-user"}}""")
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) response.header(HttpHeaders.RETRY_AFTER, "120")
        server.expect(requestTo("https://api.github.test/repos/private-owner/private-repo/collaborators/private-user/permission"))
            .andRespond(response)

        val call = { client.repositoryRole("ghu_private-token", ActorIdentity.github(42, "private-user"), GitHubRepository("private-owner", "private-repo")) }
        when (status) {
            HttpStatus.OK -> assertEquals(RepositoryRole.READER, call())
            HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN -> assertNull(call())
            HttpStatus.UNAUTHORIZED -> assertFailsWith<GitHubUserAuthenticationException> { call() }
            HttpStatus.INTERNAL_SERVER_ERROR -> assertFailsWith<GitHubIdentityApiException> { call() }
            HttpStatus.TOO_MANY_REQUESTS -> assertEquals(120L, assertFailsWith<GitHubRateLimitException> { call() }.retryAfterSeconds)
            else -> error("검증 대상이 아닌 HTTP 상태입니다.")
        }

        val outcome = if (status == HttpStatus.TOO_MANY_REQUESTS) "rate_limited" else "${status.value() / 100}xx"
        val timer = meters.get("intenttrace.github.request").tag("operation", "repository_access").tag("outcome", outcome).timer()
        assertEquals(1L, timer.count())
        assertEquals(mapOf("operation" to "repository_access", "outcome" to outcome), timer.id.tags.associate { it.key to it.value })
        assertNull(meters.find("intenttrace.github.request").tag("operation", "installation").timer())
        server.verify()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/user", "/repos/acme/intent-trace/collaborators/lim/permission"])
    fun `호출 제한은 안전한 대기 시간으로 전달하고 응답 원문을 노출하지 않는다`(path: String) {
        val properties = GitHubProperties(apiBaseUrl = URI("https://api.github.test"))
        val meters = SimpleMeterRegistry()
        val builder = RestClient.builder()
        GitHubHttpPolicy().githubRequestPolicy(properties, Clock.fixed(now, ZoneOffset.UTC), meters).customize(builder)
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = GitHubUserRestClient(GitHubHttpPolicy().githubApiRestClient(builder, properties))
        for (token in listOf("ghu_first", "ghu_second")) {
            server.expect(requestTo("https://api.github.test$path"))
                .andExpect(header("Accept", "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", properties.apiVersion))
                .andExpect(header("Authorization", "Bearer $token"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).header("Retry-After", "120").body("ghu_private-response"))
        }
        for (token in listOf("ghu_first", "ghu_second")) {
            val exception = assertFailsWith<GitHubRateLimitException> {
                if (path == "/user") client.authenticate(token)
                else client.repositoryRole(token, ActorIdentity.github(42, "lim"), GitHubRepository("acme", "intent-trace"))
            }
            assertEquals(120L, exception.retryAfterSeconds)
            assertFalse(exception.message!!.contains("private-response"))
        }
        assertEquals(2L, meters.get("intenttrace.github.request").tag("outcome", "rate_limited").timer().count())
        server.verify()
        assertNull(GitHubRateLimit.detect(403, HttpHeaders(), now))
        assertEquals(60L, GitHubRateLimit.detect(429, HttpHeaders(), now)?.retryAfterSeconds)
        val reset = HttpHeaders().also { it.set("X-RateLimit-Remaining", "0"); it.set("X-RateLimit-Reset", (now.epochSecond + 300).toString()) }
        assertEquals(300L, GitHubRateLimit.detect(403, reset, now)?.retryAfterSeconds)
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "Sat, 05 Sep 2026 00:02:00 GMT | 120",
        "Fri, 04 Sep 2026 23:59:00 GMT | 1",
        "invalid-date | 60",
    ])
    fun `Retry-After 날짜로 대기 시간을 계산하고 지난 날짜나 잘못된 값은 보정한다`(retryAfter: String, expectedSeconds: Long) {
        val headers = HttpHeaders().also { it.set(HttpHeaders.RETRY_AFTER, retryAfter) }

        assertEquals(expectedSeconds, GitHubRateLimit.detect(429, headers, now)?.retryAfterSeconds)
    }

    companion object { private val now = Instant.parse("2026-09-05T00:00:00Z") }
}
