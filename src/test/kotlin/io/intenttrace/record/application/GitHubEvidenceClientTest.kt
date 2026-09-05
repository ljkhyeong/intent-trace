package io.intenttrace.record.application

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.record.adapter.out.github.GitHubGitEvidenceClient
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubEvidenceClientTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = GitHubGitEvidenceClient(builder, GitHubProperties(apiBaseUrl = URI("https://api.github.test")),
        object : CurrentGitHubUserSession {
            override fun require() = GitHubUserSession(ActorIdentity.github(1, "test"), "ghu_test")
        }, jacksonObjectMapper())
    private val repository = GitHubRepository.parse("acme/repo")
    private val revision = "a".repeat(40)
    private val tree = "b".repeat(40)
    private val blob = "c".repeat(40)

    @Test
    fun `커밋에 고정된 전체 트리와 blob만 읽고 잘린 트리는 거부한다`() {
        for (truncated in listOf(false, true)) {
            server.reset()
            server.expect(requestTo("https://api.github.test/repos/acme/repo/git/commits/$revision"))
                .andExpect(header("Authorization", "Bearer ghu_test"))
                .andRespond(withSuccess("""{"sha":"$revision","tree":{"sha":"$tree","url":"ignored"}}""", MediaType.APPLICATION_JSON))
            server.expect(requestTo("https://api.github.test/repos/acme/repo/git/trees/$tree?recursive=1"))
                .andRespond(withSuccess("""{"sha":"$tree","truncated":$truncated,"tree":[{"path":"sample.txt","mode":"100644","type":"blob","sha":"$blob","size":2,"url":"ignored"}]}""", MediaType.APPLICATION_JSON))
            if (truncated) {
                assertFailsWith<GitHubApiException> { client.snapshot(repository, revision) }
            } else {
                assertEquals(blob, client.snapshot(repository, revision).entries["sample.txt"]?.sha)
            }
            server.verify()
        }
        server.reset()
        server.expect(requestTo("https://api.github.test/repos/acme/repo/git/blobs/$blob"))
            .andRespond(withSuccess("""{"sha":"$blob","encoding":"base64","size":2,"content":"YQo="}""", MediaType.APPLICATION_JSON))
        assertEquals("a\n", client.blob(repository, blob).toString(Charsets.UTF_8))
        server.verify()
    }
}
