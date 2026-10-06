package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubProperties
import io.intenttrace.config.GitHubHttpPolicy
import io.intenttrace.config.GitHubApiException
import io.intenttrace.publication.application.CheckRunAnnotation
import io.intenttrace.publication.application.UpsertGitHubCheckRunCommand
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.intenttrace.publication.application.ForkPullRequestUnsupportedException
import io.intenttrace.publication.application.GitHubRepositoryMismatchException
import kotlin.test.assertContains
import kotlin.test.assertFalse

class GitHubRestClientTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val tokenProvider = TestTokenProvider()
    private val client = GitHubRestClient(
        client = GitHubHttpPolicy().githubApiRestClient(builder, GitHubProperties(
            apiBaseUrl = URI.create("https://api.github.test"),
        )),
        tokenProvider = tokenProvider,
    )
    private val target = GitHubPullRequestTarget("acme", "intent-trace", 12)
    private val revision = "b".repeat(40)
    private val externalId = "intent-trace:8c766289-5c2c-4b1f-90e6-376058868c42"
    private val pullRequestUrl = "https://api.github.test/repos/acme/intent-trace/pulls/12"

    @Test
    fun `PR HEAD를 GitHub 응답에서 읽는다`() {
        server.expect(requestTo(pullRequestUrl))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer installation-token"))
            .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
            .andRespond(pullRequest())

        assertEquals(revision, client.getHeadRevision(target))
        server.verify()
    }

    @ParameterizedTest
    @ValueSource(strings = ["base", "head", "sha"])
    fun `PR 응답의 저장소명이나 커밋 형식 오류는 원문 없이 API 오류로 처리한다`(field: String) {
        val marker = "test-private-response-marker"
        server.expect(requestTo(pullRequestUrl)).andRespond(pullRequest(
            sha = if (field == "sha") marker else revision,
            head = if (field == "head") marker else "acme/intent-trace",
            base = if (field == "base") marker else "acme/intent-trace",
        ))

        val exception = assertFailsWith<GitHubApiException> { client.getHeadRevision(target) }

        assertEquals("GitHub PR 응답 형식이 올바르지 않습니다.", exception.message)
        assertFalse(exception.stackTraceToString().contains(marker))
        server.verify()
    }

    @Test
    fun `같은 external id의 Check Run이 있으면 새로 만들지 않고 갱신한다`() {
        expectList()
            .andExpect(queryParam("filter", "all"))
            .andExpect(queryParam("per_page", "100"))
            // queryParam은 인코딩된 값을 비교하므로 한글 이름은 디코딩한 쿼리로 확인한다.
            .andExpect { request -> assertContains(request.uri.query.split('&'), "check_name=IntentTrace / 변경 의도") }
            .andRespond(checkRuns(checkRunJson(77)))
        expectCheckRun(HttpMethod.PATCH, 77)
            .andExpect(content().json("""{"name":"IntentTrace / 변경 의도","external_id":"$externalId","status":"completed","conclusion":"neutral"}""", JsonCompareMode.LENIENT))
            .andExpect(jsonPath("$.head_sha").doesNotHaveJsonPath())
            .andRespond(checkRun(77))

        val result = client.upsertCheckRun(command())

        assertEquals(77L, result.id)
        server.verify()
    }

    @Test
    fun `기존 Check Run이 없으면 neutral 결과로 생성한다`() {
        expectList().andRespond(checkRuns())
        expectCheckRun(HttpMethod.POST)
            .andExpect(
                content().json(
                    """{"name":"IntentTrace / 변경 의도","head_sha":"$revision","external_id":"$externalId","status":"completed","conclusion":"neutral"}""",
                    JsonCompareMode.LENIENT,
                ),
            )
            .andExpect(jsonPath("$.output.annotations").doesNotHaveJsonPath())
            .andRespond(checkRun(88))

        val result = client.upsertCheckRun(command())

        assertEquals(88L, result.id)
        server.verify()
    }

    @Test
    fun `코드 주석은 새 Check Run에 notice 수준으로 함께 만든다`() {
        expectList().andRespond(checkRuns())
        expectCheckRun(HttpMethod.POST)
            .andExpect(
                content().json(
                    """{"output":{"annotations":[{"path":"src/App.kt","start_line":1,"end_line":4,"annotation_level":"notice","title":"변경 의도: 게시","message":"구현 결정과 이유"}]}}""",
                    JsonCompareMode.LENIENT,
                ),
            )
            .andRespond(checkRun(88))

        client.upsertCheckRun(command().copy(annotations = listOf(annotation)))

        server.verify()
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = [0, 3])
    fun `기존 Check Run에는 주석이 없다고 확인된 경우에만 주석을 보낸다`(annotationsCount: Int?) {
        val output = annotationsCount?.let { ""","output":{"annotations_count":$it}""" }.orEmpty()
        expectList().andRespond(checkRuns(checkRunJson(77, extra = output)))
        val update = expectCheckRun(HttpMethod.PATCH, 77)
        // GitHub는 수정 요청의 주석을 기존 주석 뒤에 덧붙이므로 다시 게시해도 중복되지 않아야 한다.
        if (annotationsCount == 0) {
            update.andExpect(jsonPath("$.output.annotations[0].path").value("src/App.kt"))
        } else {
            update.andExpect(jsonPath("$.output.annotations").doesNotHaveJsonPath())
        }
        update.andRespond(checkRun(77))

        client.upsertCheckRun(command().copy(annotations = listOf(annotation)))

        server.verify()
    }

    @ParameterizedTest
    @ValueSource(strings = ["잘못된 ID", "다른 HEAD", "다른 external id", "HTTP URL"])
    fun `Check Run 생성 응답이 게시 요청과 일치하지 않거나 URL이 안전하지 않으면 거부한다`(case: String) {
        expectList().andRespond(checkRuns())
        expectCheckRun(HttpMethod.POST).andRespond(withSuccess(checkRunJson(
            id = if (case == "잘못된 ID") 0 else 88,
            externalId = if (case == "다른 external id") "intent-trace:other" else externalId,
            head = if (case == "다른 HEAD") "c".repeat(40) else revision,
            url = if (case == "HTTP URL") "http://github.test/check-runs/88" else "https://github.test/check-runs/88",
        ), MediaType.APPLICATION_JSON))

        val exception = assertFailsWith<GitHubApiException> { client.upsertCheckRun(command()) }

        val expectedMessage = if (case == "HTTP URL") {
            "GitHub Check Run 응답 URL 형식이 올바르지 않습니다."
        } else {
            "GitHub Check Run 응답이 게시 요청과 일치하지 않습니다."
        }
        assertEquals(expectedMessage, exception.message)
        server.verify()
    }

    @Test
    fun `저장된 Check Run ID는 external id와 HEAD를 확인한 뒤 갱신한다`() {
        expectCheckRun(HttpMethod.GET, 55).andRespond(checkRun(55))
        expectCheckRun(HttpMethod.PATCH, 55).andRespond(checkRun(55))

        val result = client.upsertCheckRun(command().copy(knownCheckRunId = 55))

        assertEquals(55L, result.id)
        server.verify()
    }

    @Test
    fun `목록에서 찾은 Check Run 수정이 404면 새로 만들지 않고 실패한다`() {
        expectList().andRespond(checkRuns(checkRunJson(77)))
        expectCheckRun(HttpMethod.PATCH, 77).andRespond(withStatus(HttpStatus.NOT_FOUND))

        val exception = assertFailsWith<GitHubApiException> { client.upsertCheckRun(command()) }

        assertEquals("GitHub Check Run 수정 요청이 실패했습니다. HTTP 404", exception.message)
        server.verify()
    }

    @Test
    fun `Check Run 검색 한도를 채우면 중복 생성하지 않는다`() {
        val fullPage = checkRuns(*(1..100).map { id -> checkRunJson(id.toLong(), externalId = "다른-기록-$id") }.toTypedArray())
        repeat(10) { pageIndex -> expectList(page = pageIndex + 1).andRespond(fullPage) }

        val exception = assertFailsWith<GitHubApiException> {
            client.upsertCheckRun(command("intent-trace:찾을-수-없는-기록"))
        }

        assertContains(exception.message.orEmpty(), "검색 한도")
        server.verify()
    }

    @ParameterizedTest
    @ValueSource(strings = ["다른 기록", "조회 404", "수정 404"])
    fun `저장된 Check Run을 쓸 수 없으면 목록에서 올바른 실행을 다시 찾는다`(case: String) {
        val known = expectCheckRun(HttpMethod.GET, 55)
        when (case) {
            "조회 404" -> known.andRespond(withStatus(HttpStatus.NOT_FOUND))
            "다른 기록" -> known.andRespond(checkRun(55, externalId = "intent-trace:다른-기록"))
            else -> known.andRespond(checkRun(55))
        }
        if (case == "수정 404") {
            expectCheckRun(HttpMethod.PATCH, 55).andRespond(withStatus(HttpStatus.NOT_FOUND))
        }
        expectList().andRespond(checkRuns(checkRunJson(77)))
        expectCheckRun(HttpMethod.PATCH, 77).andRespond(checkRun(77))

        val result = client.upsertCheckRun(command().copy(knownCheckRunId = 55))

        assertEquals(77L, result.id)
        server.verify()
    }

    @Test
    fun `installation token이 거부되면 폐기하고 한 번만 다시 요청한다`() {
        server.expect(requestTo(pullRequestUrl))
            .andExpect(header("Authorization", "Bearer installation-token"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        server.expect(requestTo(pullRequestUrl))
            .andExpect(header("Authorization", "Bearer refreshed-token"))
            .andRespond(pullRequest())

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
            server.expect(requestTo(pullRequestUrl))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
            assertFailsWith(expected) { client.getHeadRevision(target) }
            server.verify()
        }
    }

    @Test
    fun `대체 안내는 다른 기록의 Check Run에 쓰거나 새로 생성하지 않는다`() {
        expectCheckRun(HttpMethod.GET, 55).andRespond(checkRun(55, externalId = "other"))
        assertFailsWith<IllegalStateException> { client.updateExistingCheckRun(command().copy(knownCheckRunId = 55)) }
        server.verify()
    }

    @ParameterizedTest
    @ValueSource(strings = ["목록 조회", "개별 조회", "수정"])
    fun `Check Run 응답 파싱 오류에 원문을 남기지 않는다`(operation: String) {
        val marker = "test-private-response-marker"
        if (operation == "수정") expectCheckRun(HttpMethod.GET, 55).andRespond(checkRun(55))
        if (operation == "목록 조회") {
            expectList().andRespond(withSuccess("""{"check_runs":[{"id":"$marker"}]}""", MediaType.APPLICATION_JSON))
        } else {
            expectCheckRun(if (operation == "수정") HttpMethod.PATCH else HttpMethod.GET, 55)
                .andRespond(withSuccess("""{"id":"$marker"}""", MediaType.APPLICATION_JSON))
        }

        val exception = assertFailsWith<GitHubApiException> {
            client.upsertCheckRun(command().copy(knownCheckRunId = if (operation == "목록 조회") null else 55))
        }

        val action = if (operation == "수정") "수정" else "조회"
        assertEquals("GitHub Check Run $action 요청을 완료하지 못했습니다.", exception.message)
        assertFalse(exception.stackTraceToString().contains(marker))
        server.verify()
    }

    private val annotation = CheckRunAnnotation("src/App.kt", 1, 4, "변경 의도: 게시", "구현 결정과 이유")

    private fun command(externalId: String = this.externalId) = UpsertGitHubCheckRunCommand(
        target = target,
        headRevision = revision,
        externalId = externalId,
        knownCheckRunId = null,
        title = "변경 의도",
        summary = "작성자가 확인했습니다.",
        markdown = "# 변경 의도",
    )

    private fun pullRequest(sha: String = revision, head: String = "acme/intent-trace", base: String = "Acme/Intent-Trace") = withSuccess(
        """{"head":{"sha":"$sha","repo":{"id":1,"full_name":"$head"}},"base":{"repo":{"id":1,"full_name":"$base"}}}""",
        MediaType.APPLICATION_JSON,
    )

    /** 기록 커밋의 Check Run 목록 조회를 기대한다. 다른 경로와 구분되도록 `?`까지 비교한다. */
    private fun expectList(page: Int = 1) =
        server.expect(requestTo(startsWith("https://api.github.test/repos/acme/intent-trace/commits/$revision/check-runs?")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("page", page.toString()))

    private fun expectCheckRun(httpMethod: HttpMethod, id: Long? = null) =
        server.expect(requestTo("https://api.github.test/repos/acme/intent-trace/check-runs" + id?.let { "/$it" }.orEmpty()))
            .andExpect(method(httpMethod))

    private fun checkRunJson(
        id: Long,
        externalId: String = this.externalId,
        head: String = revision,
        url: String = "https://github.test/check-runs/$id",
        extra: String = "",
    ) = """{"id":$id,"head_sha":"$head","html_url":"$url","external_id":"$externalId"$extra}"""

    private fun checkRun(id: Long, externalId: String = this.externalId) =
        withSuccess(checkRunJson(id, externalId), MediaType.APPLICATION_JSON)

    private fun checkRuns(vararg runs: String) =
        withSuccess(runs.joinToString(",", "{\"check_runs\":[", "]}"), MediaType.APPLICATION_JSON)

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
