package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.publication.application.GitHubRepositoryMismatchException
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.junit.jupiter.api.Test
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
    private val client = GitHubUserPullRequestClient(builder, GitHubProperties(), object : CurrentGitHubUserSession {
        override fun require() = GitHubUserSession(ActorIdentity.github(1, "owner"), "ghu_test")
    }, jacksonObjectMapper())
    private val target = GitHubPullRequestTarget("acme", "repo", 1)
    private val sha = "a".repeat(40)

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
