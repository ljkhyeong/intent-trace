package io.intenttrace.config

import io.intenttrace.identity.adapter.out.github.GitHubUserRestClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.client.MockRestServiceServer
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
    @Test
    fun `호출 제한은 안전한 대기 시간으로 전달하고 응답 원문을 노출하지 않는다`() {
        val properties = GitHubProperties(apiBaseUrl = URI("https://api.github.test"))
        val meters = SimpleMeterRegistry()
        val builder = RestClient.builder()
        GitHubHttpPolicy().githubRequestPolicy(properties, Clock.fixed(now, ZoneOffset.UTC), meters).customize(builder)
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = GitHubUserRestClient(builder, properties)
        server.expect(requestTo("https://api.github.test/user"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).header("Retry-After", "120").body("ghu_private-response"))
        val exception = assertFailsWith<GitHubRateLimitException> { client.authenticate("ghu_test") }
        assertEquals(120L, exception.retryAfterSeconds)
        assertFalse(exception.message!!.contains("private-response"))
        assertEquals(1L, meters.get("intenttrace.github.request").tag("outcome", "rate_limited").timer().count())
        server.verify()
        assertNull(GitHubRateLimit.detect(403, HttpHeaders(), now))
        assertEquals(60L, GitHubRateLimit.detect(429, HttpHeaders(), now)?.retryAfterSeconds)
        val reset = HttpHeaders().also { it.set("X-RateLimit-Remaining", "0"); it.set("X-RateLimit-Reset", (now.epochSecond + 300).toString()) }
        assertEquals(300L, GitHubRateLimit.detect(403, reset, now)?.retryAfterSeconds)
    }

    companion object { private val now = Instant.parse("2026-09-05T00:00:00Z") }
}
