package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubProperties
import io.intenttrace.config.GitHubHttpPolicy
import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.publication.application.GitHubRepositoryMismatchException
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class GitHubUserPullRequestClientTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = GitHubUserPullRequestClient(GitHubHttpPolicy().githubApiRestClient(builder, GitHubProperties()), object : CurrentGitHubUserSession {
        override fun require() = GitHubUserSession(ActorIdentity.github(1, "owner"), "ghu_test")
    }, jacksonObjectMapper())
    private val target = GitHubPullRequestTarget("acme", "repo", 1)
    private val sha = "a".repeat(40)

    @ParameterizedTest
    @ValueSource(strings = ["base", "head", "sha"])
    fun `PR 응답의 저장소명이나 커밋 형식 오류는 원문 없이 API 오류로 처리한다`(field: String) {
        val marker = "test-private-response-marker"
        val base = if (field == "base") marker else "acme/repo"
        val head = if (field == "head") marker else "acme/repo"
        val revision = if (field == "sha") marker else sha
        server.expect(requestTo("https://api.github.com/repos/acme/repo/pulls/1"))
            .andRespond(withSuccess("""{"base":{"sha":"$sha","repo":{"id":1,"full_name":"$base"}},"head":{"sha":"$revision","repo":{"id":1,"full_name":"$head"}}}""", MediaType.APPLICATION_JSON))

        val exception = assertFailsWith<GitHubApiException> { client.read(target) }

        assertEquals("GitHub PR 응답 형식이 올바르지 않습니다.", exception.message)
        assertFalse(exception.stackTraceToString().contains(marker))
        server.verify()
    }

    @Test
    fun `사용자 권한으로 PR 커밋과 Fork 여부를 읽고 다른 저장소 응답을 거부한다`() {
        fun response(base: String, head: String, id: Int) = """{"base":{"sha":"$sha","repo":{"id":1,"full_name":"$base"}},"head":{"sha":"$sha","repo":{"id":$id,"full_name":"$head"}}}"""
        for (body in listOf(response("Acme/Repo", "acme/repo", 1), response("acme/repo", "other/repo", 2), response("other/repo", "other/repo", 1))) {
            server.expect(requestTo("https://api.github.com/repos/acme/repo/pulls/1"))
                .andExpect(header("Authorization", "Bearer ghu_test"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
        }
        val same = client.read(target)
        assertEquals(sha, same.headRevision)
        assertFalse(same.fork)
        assertTrue(client.read(target).fork)
        assertFailsWith<GitHubRepositoryMismatchException> { client.read(target) }
        server.verify()
    }
}
