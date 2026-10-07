package io.intenttrace.config

import io.intenttrace.identity.adapter.out.github.GitHubUserRestClient
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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
    @ValueSource(strings = ["/user", "/repos/acme/intent-trace/collaborators/lim/permission"])
    fun `호출 제한은 안전한 대기 시간으로 전달하고 응답 원문을 노출하지 않는다`(path: String) {
        val properties = GitHubProperties(apiBaseUrl = URI("https://api.github.test"))
        val builder = RestClient.builder()
        GitHubHttpPolicy().githubRequestPolicy(properties, Clock.fixed(now, ZoneOffset.UTC)).customize(builder)
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = GitHubUserRestClient(GitHubHttpPolicy().githubApiRestClient(builder, properties))
        for (token in listOf("ghu_first", "ghu_second")) {
            server.expect(requestTo("https://api.github.test$path"))
                .andExpect(header("Accept", "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
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
