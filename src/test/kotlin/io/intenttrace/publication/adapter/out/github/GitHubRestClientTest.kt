package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubProperties
import io.intenttrace.publication.application.UpsertGitHubCheckRunCommand
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.intenttrace.publication.application.ForkPullRequestUnsupportedException
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.publication.application.GitHubRepositoryMismatchException

class GitHubRestClientTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val tokenProvider = TestTokenProvider()
    private val client = GitHubRestClient(
        restClientBuilder = builder,
        properties = GitHubProperties(
            apiBaseUrl = URI.create("https://api.github.test"),
            apiVersion = "2026-03-10",
        ),
        tokenProvider = tokenProvider,
    )
    private val target = GitHubPullRequestTarget("acme", "intent-trace", 12)
    private val revision = "b".repeat(40)

    @Test
    fun `PR HEAD를 GitHub 응답에서 읽는다`() {
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/pulls/12"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer installation-token"))
            .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
            .andRespond(withSuccess("""{"head":{"sha":"$revision","repo":{"id":1,"full_name":"acme/intent-trace"}},"base":{"repo":{"id":1,"full_name":"Acme/Intent-Trace"}}}""", MediaType.APPLICATION_JSON))

        assertEquals(revision, client.getHeadRevision(target))
        server.verify()
    }

    @Test
    fun `같은 external id의 Check Run이 있으면 새로 만들지 않고 갱신한다`() {
        val externalId = "intent-trace:8c766289-5c2c-4b1f-90e6-376058868c42"
        server.expect { request ->
            assertEquals("/repos/acme/intent-trace/commits/$revision/check-runs", request.uri.path)
            val query = UriComponentsBuilder.fromUri(request.uri).build().queryParams
            assertEquals(
                "IntentTrace / 변경 의도",
                UriUtils.decode(query.getFirst("check_name")!!, Charsets.UTF_8),
            )
            assertEquals("all", query.getFirst("filter"))
            assertEquals("100", query.getFirst("per_page"))
            assertEquals("1", query.getFirst("page"))
        }
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"check_runs":[{"id":77,"head_sha":"$revision","html_url":"https://github.test/check-runs/77","external_id":"$externalId"}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/check-runs/77"))
            .andExpect(method(HttpMethod.PATCH))
            .andExpect(content().json("""{"name":"IntentTrace / 변경 의도","external_id":"$externalId","status":"completed","conclusion":"neutral"}""", JsonCompareMode.LENIENT))
            .andRespond(
                withSuccess(
                    """{"id":77,"head_sha":"$revision","html_url":"https://github.test/check-runs/77","external_id":"$externalId"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.upsertCheckRun(command(externalId))

        assertEquals(77L, result.id)
        server.verify()
    }

    @Test
    fun `기존 Check Run이 없으면 neutral 결과로 생성한다`() {
        val externalId = "intent-trace:8c766289-5c2c-4b1f-90e6-376058868c42"
        server.expect { request ->
            assertEquals("/repos/acme/intent-trace/commits/$revision/check-runs", request.uri.path)
        }
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""{"check_runs":[]}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/check-runs"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """{"name":"IntentTrace / 변경 의도","head_sha":"$revision","external_id":"$externalId","status":"completed","conclusion":"neutral"}""",
                    JsonCompareMode.LENIENT,
                ),
            )
            .andRespond(
                withSuccess(
                    """{"id":88,"head_sha":"$revision","html_url":"https://github.test/check-runs/88","external_id":"$externalId"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.upsertCheckRun(command(externalId))

        assertEquals(88L, result.id)
        server.verify()
    }

    @Test
    fun `저장된 Check Run ID는 external id와 HEAD를 확인한 뒤 갱신한다`() {
        val externalId = "intent-trace:8c766289-5c2c-4b1f-90e6-376058868c42"
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/check-runs/55"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"id":55,"head_sha":"$revision","html_url":"https://github.test/check-runs/55","external_id":"$externalId"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/check-runs/55"))
            .andExpect(method(HttpMethod.PATCH))
            .andRespond(
                withSuccess(
                    """{"id":55,"head_sha":"$revision","html_url":"https://github.test/check-runs/55","external_id":"$externalId"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.upsertCheckRun(command(externalId).copy(knownCheckRunId = 55))

        assertEquals(55L, result.id)
        server.verify()
    }

    @Test
    fun `installation token이 거부되면 폐기하고 한 번만 다시 요청한다`() {
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/pulls/12"))
            .andExpect(header("Authorization", "Bearer installation-token"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/pulls/12"))
            .andExpect(header("Authorization", "Bearer refreshed-token"))
            .andRespond(withSuccess("""{"head":{"sha":"$revision","repo":{"id":1,"full_name":"acme/intent-trace"}},"base":{"repo":{"id":1,"full_name":"Acme/Intent-Trace"}}}""", MediaType.APPLICATION_JSON))

        assertEquals(revision, client.getHeadRevision(target))
        assertEquals(listOf("installation-token"), tokenProvider.invalidatedTokens)
        server.verify()
    }

    @Test
    fun `Fork와 다른 base 저장소와 누락된 저장소 응답을 거부한다`() {
        val responses = listOf(
            """{"head":{"sha":"$revision","repo":{"id":2,"full_name":"fork/intent-trace"}},"base":{"repo":{"id":1,"full_name":"acme/intent-trace"}}}""" to ForkPullRequestUnsupportedException::class,
            """{"head":{"sha":"$revision","repo":{"id":1,"full_name":"other/repo"}},"base":{"repo":{"id":1,"full_name":"other/repo"}}}""" to GitHubRepositoryMismatchException::class,
            """{"head":{"sha":"$revision"}}""" to GitHubApiException::class,
        )
        responses.forEach { (body, expected) ->
            server.reset()
            server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/pulls/12"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
            assertFailsWith(expected) { client.getHeadRevision(target) }
            server.verify()
        }
    }

    private fun command(externalId: String) = UpsertGitHubCheckRunCommand(
        target = target,
        headRevision = revision,
        externalId = externalId,
        knownCheckRunId = null,
        title = "변경 의도",
        summary = "작성자가 확인했습니다.",
        markdown = "# 변경 의도",
    )

    private class TestTokenProvider : GitHubAccessTokenProvider {
        private var currentToken = "installation-token"
        val invalidatedTokens = mutableListOf<String>()

        override fun token(target: GitHubPullRequestTarget): String = currentToken

        override fun invalidate(target: GitHubPullRequestTarget, rejectedToken: String): Boolean {
            invalidatedTokens += rejectedToken
            currentToken = "refreshed-token"
            return true
        }
    }
}
