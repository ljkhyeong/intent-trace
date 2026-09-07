package io.intenttrace.record.application

import io.intenttrace.config.GitHubHttpPolicy
import io.intenttrace.config.GitHubProperties
import io.intenttrace.config.GitHubRateLimitException
import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.record.adapter.out.github.GitHubContextClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import kotlin.test.*

class GitHubContextClientTest {
    private val properties = GitHubProperties()
    private val builder = RestClient.builder().also {
        GitHubHttpPolicy().githubRequestPolicy(properties, Clock.systemUTC(), SimpleMeterRegistry()).customize(it)
    }
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val mapper = jacksonObjectMapper()
    private val client = GitHubContextClient(GitHubHttpPolicy().githubApiRestClient(builder, properties), object : CurrentGitHubUserSession {
        override fun require() = GitHubUserSession(ActorIdentity.github(42, "lim"), "ghu_context-test")
    }, mapper, properties)
    private val repository = GitHubRepository.parse("acme/intent-trace")
    private val revision = "a".repeat(40)

    @Test
    fun `같은 조회 API에서 이슈와 PR을 구분하고 원문 대상을 확인한다`() {
        for (kind in GitHubRequestKind.entries) {
            val body = requestBody(kind)
            expect("/issues/7").andRespond(withSuccess(mapper.writeValueAsString(body), MediaType.APPLICATION_JSON))
        }
        expect("/issues/7").andRespond(withSuccess(mapper.writeValueAsString(requestBody(GitHubRequestKind.ISSUE) +
            ("html_url" to "https://github.com/other/private/issues/7")), MediaType.APPLICATION_JSON))
        for (kind in GitHubRequestKind.entries) {
            val result = client.request(repository, 7)
            assertEquals(kind, result.kind)
            assertNull(result.body)
            assertEquals("제목", result.title)
        }
        assertFailsWith<GitHubApiException> { client.request(repository, 7) }
        server.verify()
    }

    @Test
    fun `CI 조회는 전체 커밋 필터와 페이지를 사용하고 다른 저장소나 커밋을 거부한다`() {
        val suffix = "/actions/runs?head_sha=$revision&per_page=20&page=2"
        for ((repo, sha) in listOf(repository.key to revision, "other/repo" to revision, repository.key to "b".repeat(40))) {
            val run = mapOf("id" to 8, "run_attempt" to 2, "name" to "서버 검증", "head_sha" to sha,
                "event" to "pull_request", "status" to "completed", "conclusion" to "failure",
                "run_started_at" to "2026-09-07T00:00:00Z", "updated_at" to "2026-09-07T00:01:00Z",
                "repository" to mapOf("full_name" to repo), "html_url" to "https://untrusted.example/run")
            expect(suffix).andRespond(withSuccess(mapper.writeValueAsString(mapOf("total_count" to 21, "workflow_runs" to listOf(run))), MediaType.APPLICATION_JSON))
        }
        for ((repo, sha) in listOf(repository.key to revision, "other/repo" to revision, repository.key to "b".repeat(40))) {
            if (repo == repository.key && sha == revision) {
                val result = client.actions(repository, revision, 2).items.single()
                assertEquals(2, result.attempt)
                assertEquals("failure", result.conclusion)
                assertEquals("https://github.com/acme/intent-trace/actions/runs/8", result.url)
            } else assertFailsWith<GitHubApiException> { client.actions(repository, revision, 2) }
        }
        server.verify()
    }

    @Test
    fun `권한과 외부 오류 및 크기 초과는 비밀값 없이 실패하고 호출 제한은 보존한다`() {
        expect("/issues/7").andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        for (status in listOf(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND, HttpStatus.BAD_GATEWAY)) {
            expect("/issues/7").andRespond(withStatus(status).body("secret=remote-secret"))
        }
        expect("/issues/7").andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "12"))
        expect("/issues/7").andRespond(withSuccess("x".repeat(2 * 1024 * 1024 + 1), MediaType.APPLICATION_JSON))
        expect("/issues/7").andRespond(withSuccess("{invalid", MediaType.APPLICATION_JSON))
        assertFailsWith<GitHubUserAuthenticationException> { client.request(repository, 7) }
        repeat(3) {
            val error = assertFailsWith<GitHubApiException> { client.request(repository, 7) }
            assertFalse(error.message.orEmpty().contains("remote-secret"))
        }
        assertEquals(12L, assertFailsWith<GitHubRateLimitException> { client.request(repository, 7) }.retryAfterSeconds)
        assertTrue(assertFailsWith<GitHubApiException> { client.request(repository, 7) }.message.orEmpty().contains("2 MiB"))
        assertTrue(assertFailsWith<GitHubApiException> { client.request(repository, 7) }.message.orEmpty().contains("응답 형식"))
        server.verify()
    }

    private fun expect(suffix: String) = server.expect(requestTo("https://api.github.com/repos/${repository.key}$suffix"))
        .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer ghu_context-test"))

    private fun requestBody(kind: GitHubRequestKind) = mapOf("number" to 7, "title" to "제목", "body" to null,
        "updated_at" to "2026-09-07T00:00:00Z", "pull_request" to if (kind == GitHubRequestKind.PULL_REQUEST) emptyMap<String, String>() else null,
        "html_url" to "https://github.com/acme/intent-trace/${if (kind == GitHubRequestKind.ISSUE) "issues" else "pull"}/7")
}
