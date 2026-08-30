package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubProperties
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.publication.application.UpsertGitHubCheckRunCommand
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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
            .andRespond(withSuccess("""{"head":{"sha":"$revision"}}""", MediaType.APPLICATION_JSON))

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
    fun `Check Run 검색 한도를 채우면 중복 생성하지 않는다`() {
        val response = (1..100).joinToString(",", prefix = "{\"check_runs\":[", postfix = "]}") { id ->
            """{"id":$id,"head_sha":"$revision","html_url":"https://github.test/check-runs/$id","external_id":"다른-기록-$id"}"""
        }
        repeat(10) { pageIndex ->
            server.expect { request ->
                val query = UriComponentsBuilder.fromUri(request.uri).build().queryParams
                assertEquals((pageIndex + 1).toString(), query.getFirst("page"))
            }
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON))
        }

        val exception = assertFailsWith<GitHubApiException> {
            client.upsertCheckRun(command("intent-trace:찾을-수-없는-기록"))
        }

        assertContains(exception.message.orEmpty(), "검색 한도")
        server.verify()
    }

    @Test
    fun `저장된 Check Run ID가 다른 기록이면 목록에서 올바른 실행을 다시 찾는다`() {
        val externalId = "intent-trace:8c766289-5c2c-4b1f-90e6-376058868c42"
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/check-runs/55"))
            .andRespond(
                withSuccess(
                    """{"id":55,"head_sha":"$revision","html_url":"https://github.test/check-runs/55","external_id":"intent-trace:다른-기록"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server.expect { request ->
            assertEquals("/repos/acme/intent-trace/commits/$revision/check-runs", request.uri.path)
        }.andRespond(
            withSuccess(
                """{"check_runs":[{"id":77,"head_sha":"$revision","html_url":"https://github.test/check-runs/77","external_id":"$externalId"}]}""",
                MediaType.APPLICATION_JSON,
            ),
        )
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/check-runs/77"))
            .andExpect(method(HttpMethod.PATCH))
            .andRespond(
                withSuccess(
                    """{"id":77,"head_sha":"$revision","html_url":"https://github.test/check-runs/77","external_id":"$externalId"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.upsertCheckRun(command(externalId).copy(knownCheckRunId = 55))

        assertEquals(77L, result.id)
        server.verify()
    }

    @Test
    fun `installation token이 거부되면 폐기하고 한 번만 다시 요청한다`() {
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/pulls/12"))
            .andExpect(header("Authorization", "Bearer installation-token"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/pulls/12"))
            .andExpect(header("Authorization", "Bearer refreshed-token"))
            .andRespond(withSuccess("""{"head":{"sha":"$revision"}}""", MediaType.APPLICATION_JSON))

        assertEquals(revision, client.getHeadRevision(target))
        assertEquals(listOf("installation-token"), tokenProvider.invalidatedTokens)
        server.verify()
    }

    @ParameterizedTest
    @ValueSource(strings = ["목록 조회", "개별 조회", "수정"])
    fun `Check Run 응답 파싱 오류에 원문을 남기지 않는다`(operation: String) {
        val marker = "test-private-response-marker"
        val externalId = "intent-trace:test-record"
        if (operation == "수정") {
            server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/check-runs/55"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                    withSuccess(
                        """{"id":55,"head_sha":"$revision","html_url":"https://github.test/check-runs/55","external_id":"$externalId"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )
        }
        val path = if (operation == "목록 조회") {
            "/repos/acme/intent-trace/commits/$revision/check-runs"
        } else {
            "/repos/acme/intent-trace/check-runs/55"
        }
        val body = if (operation == "목록 조회") {
            """{"check_runs":[{"id":"$marker"}]}"""
        } else {
            """{"id":"$marker"}"""
        }
        server.expect { request -> assertEquals(path, request.uri.path) }
            .andExpect(method(if (operation == "수정") HttpMethod.PATCH else HttpMethod.GET))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

        val exception = assertFailsWith<GitHubApiException> {
            client.upsertCheckRun(command(externalId).copy(knownCheckRunId = if (operation == "목록 조회") null else 55))
        }

        val action = if (operation == "수정") "수정" else "조회"
        assertEquals("GitHub Check Run $action 요청을 완료하지 못했습니다.", exception.message)
        assertFalse(exception.stackTraceToString().contains(marker))
        server.verify()
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
