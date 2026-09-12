package io.intenttrace.record.application

import io.intenttrace.IntentTraceApplication
import io.intenttrace.config.GitHubRateLimitException
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.identity.adapter.`in`.web.BROWSER_SESSION_COOKIE
import io.intenttrace.identity.application.*
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.util.HtmlUtils
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@SpringBootTest(classes = [IntentTraceApplication::class, GitHubContextIntegrationTest.Configuration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.datasource.url=jdbc:h2:mem:github-context;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "server.shutdown=immediate"])
@AutoConfigureMockMvc
class GitHubContextIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val sessions: GitHubUserSessionStore,
    @Autowired private val gateway: FakeContextGateway, @LocalServerPort private val port: Int) {

    @Test
    fun `REST는 정제한 이슈·PR 내용만 반환하고 권한 없는 요청과 쓰기 요청을 거부한다`() {
        val endpoint = "/api/v1/github/request-context?repositoryKey=acme/intent-trace&number=7"
        mvc.get(endpoint).andExpect { status { isUnauthorized() } }
        val token = session()
        val result = mvc.get(endpoint) { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }; jsonPath("$.authorConfirmed") { value(false) }; jsonPath("$.truncated") { value(true) }
            jsonPath("$.requestSummary") { value(containsString("출처: https://github.com/acme/intent-trace/pull/7")) }
        }.andReturn().response.contentAsString
        assertFalse(result.contains("remote-secret")); assertFalse(result.contains("/Users/owner"))
        val calls = gateway.calls.get()
        mvc.get(endpoint.replace("acme/intent-trace", "other/private")) { header("Authorization", "Bearer $token") }
            .andExpect { status { isForbidden() } }
        mvc.get(endpoint.replace("number=7", "number=0")) { header("Authorization", "Bearer $token") }
            .andExpect { status { isBadRequest() } }
        mvc.post(endpoint) { header("Authorization", "Bearer $token") }.andExpect { status { isMethodNotAllowed() } }
        assertEquals(calls, gateway.calls.get())
    }

    @Test
    fun `자료 없음과 권한 거부는 재조회 안내 없이 반환하고 일시 장애는 재조회를 유지한다`() {
        val token = session()
        val cookie = Cookie(BROWSER_SESSION_COOKIE, session(SessionChannel.BROWSER))
        for ((code, message) in listOf(403 to "GitHub 자료 조회가 거부됐습니다", 404 to "GitHub 자료가 없거나 열람할 수 없습니다",
            502 to "GitHub", 429 to "12초 후")) {
            val query = "?repositoryKey=acme/intent-trace&number=$code"
            mvc.get("/api/v1/github/request-context$query") { header("Authorization", "Bearer $token") }.andExpect {
                status { isEqualTo(code) }; jsonPath("$.detail") { value(containsString(message)) }
            }
            val page = mvc.get("/records/github$query") { cookie(cookie) }.andExpect {
                status { isEqualTo(code) }; header { string("Cache-Control", "no-store") }
                content { string(containsString(message)) }
                if (code == 429) header { string("Retry-After", "12") }
            }.andReturn().response.contentAsString
            assertEquals(code in setOf(429, 502), page.contains(">다시 조회</a>"))
        }
        mvc.get("/api/v1/github/request-context?repositoryKey=acme/intent-trace&number=7") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `CI 결과는 재실행과 조회 한도를 보존하고 로컬 검증으로 표시하지 않는다`() {
        val token = session()
        val endpoint = "/api/v1/github/actions?repositoryKey=acme/intent-trace&revision=$revision"
        mvc.get(endpoint) { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }; jsonPath("$.nextPage") { value(2) }; jsonPath("$.source") { value("GITHUB_ACTIONS") }
            jsonPath("$.snapshotVerified") { value(false) }; jsonPath("$.searchLimited") { value(true) }
            jsonPath("$.items[0].attempt") { value(2) }; jsonPath("$.items[0].conclusion") { value("failure") }
            jsonPath("$.items[0].exitCode") { doesNotExist() }; jsonPath("$.items[0].outputDigest") { doesNotExist() }
        }
        mvc.get("$endpoint&page=50") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }; jsonPath("$.nextPage") { isEmpty() }
        }
        val calls = gateway.calls.get()
        mvc.get("$endpoint&page=51") { header("Authorization", "Bearer $token") }.andExpect { status { isBadRequest() } }
        mvc.get(endpoint.replace(revision, "abc")) { header("Authorization", "Bearer $token") }.andExpect { status { isBadRequest() } }
        assertEquals(calls, gateway.calls.get())
    }

    @Test
    fun `브라우저는 외부 본문을 이스케이프하고 쿠키를 REST 인증에 사용하지 않는다`() {
        val cookie = Cookie(BROWSER_SESSION_COOKIE, session(SessionChannel.BROWSER))
        val page = mvc.get("/records/github?repositoryKey=acme/intent-trace&number=7&revision=$revision") { cookie(cookie) }.andExpect {
            status { isOk() }; header { string("Cache-Control", "no-store") }
            content { string(containsString("작성자 확인과 공개는 별도로")) }
            content { string(containsString("2차 실행 · PR")) }; content { string(containsString("&lt;script&gt;")) }
        }.andReturn().response.contentAsString
        assertFalse(page.contains("<script>")); assertFalse(page.contains("remote-secret"))
        for (status in listOf("실패", "실행 대기", "실행 중", "결과 미확인", "진행 상태: future_status")) {
            assertContains(page, "<span class=\"status\">$status</span>")
        }
        assertEquals("/records/pull-requests?repositoryKey=acme%2Fintent-trace&pullNumber=7",
            link(page, "이 PR의 변경 기록 보기"))
        val issue = mvc.get("/records/github?repositoryKey=acme/intent-trace&number=8") { cookie(cookie) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(issue, "이슈 #8")
        assertFalse(issue.contains("이 PR의 변경 기록 보기"))
        mvc.get("/api/v1/github/request-context?repositoryKey=acme/intent-trace&number=7") { cookie(cookie) }
            .andExpect { status { isUnauthorized() } }
        val directory = Path.of("build/reports/github-context")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("github.html"), page.replace("/assets/record-browser.css", "record-browser.css"))
        Files.copy(Path.of("src/main/resources/static/assets/record-browser.css"), directory.resolve("record-browser.css"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    @Test
    fun `브라우저 CI 결과는 같은 커밋으로 페이지를 왕복하고 현재 페이지를 다시 조회한다`() {
        val cookie = Cookie(BROWSER_SESSION_COOKIE, session(SessionChannel.BROWSER))
        val endpoint = "/records/github?repositoryKey=acme%2Fintent-trace&revision=$revision"
        fun get(url: String): String = mvc.get(URI(url)) { cookie(cookie) }
            .andExpect { status { isOk() }; header { string("Cache-Control", "no-store") } }
            .andReturn().response.contentAsString

        val first = get(endpoint)
        assertContains(first, "1페이지 · 20건")
        assertFalse(first.contains("이전 실행 결과"))
        val next = link(first, "다음 실행 결과")
        assertEquals("$endpoint&page=2", next)
        val second = get(next)
        assertContains(second, "2페이지 · 20건")
        assertContains(second, "https://github.com/acme/intent-trace/actions/runs/21")

        val refresh = link(second, "결과 새로고침")
        assertEquals(next, refresh)
        val calls = gateway.calls.get()
        assertContains(get(refresh), "2페이지 · 20건")
        assertEquals(calls + 1, gateway.calls.get())

        val previous = link(second, "이전 실행 결과")
        assertEquals("$endpoint&page=1", previous)
        assertContains(get(previous), "1페이지 · 20건")

        val last = get("$endpoint&page=50")
        assertContains(last, "50페이지 · 20건")
        assertFalse(last.contains("다음 실행 결과"))
        assertEquals("$endpoint&page=49", link(last, "이전 실행 결과"))
        assertEquals("$endpoint&page=50", link(last, "결과 새로고침"))
    }

    @Test
    fun `표준 MCP SDK가 새 도구의 스키마와 기본 페이지를 실제 서버에서 사용한다`() {
        val script = """
            import assert from 'node:assert/strict';
            import { Client } from '@modelcontextprotocol/sdk/client/index.js';
            import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
            const client = new Client({ name: 'github-context-test', version: '1' });
            const transport = new StreamableHTTPClientTransport(new URL('http://127.0.0.1:$port/mcp'), {
                requestInit: { headers: { Authorization: 'Bearer ' + process.env.INTENT_TRACE_SESSION_TOKEN } }
            });
            try {
                await client.connect(transport);
                const { tools } = await client.listTools();
                for (const name of ['get_github_request_context', 'list_github_actions_runs']) {
                    const tool = tools.find(t => t.name === name);
                    assert.equal(tool.outputSchema.type, 'object');
                    assert.equal(tool.annotations.readOnlyHint, true);
                }
                const request = await client.callTool({ name: 'get_github_request_context', arguments: { repositoryKey: 'acme/intent-trace', number: 7 } });
                assert.notEqual(request.isError, true);
                const draft = request.structuredContent ?? JSON.parse(request.content.find(c => c.type === 'text').text);
                assert.equal(draft.authorConfirmed, false);
                assert.ok(draft.requestSummary.includes('출처:'));
                const result = await client.callTool({ name: 'list_github_actions_runs', arguments: { repositoryKey: 'acme/intent-trace', revision: '$revision' } });
                assert.notEqual(result.isError, true);
                const runs = result.structuredContent ?? JSON.parse(result.content.find(c => c.type === 'text').text);
                assert.equal(runs.nextPage, 2);
                assert.equal(runs.snapshotVerified, false);
                for (const [number, message] of [[403, 'GitHub 자료 조회가 거부됐습니다'], [404, 'GitHub 자료가 없거나 열람할 수 없습니다']]) {
                    const failure = await client.callTool({ name: 'get_github_request_context', arguments: { repositoryKey: 'acme/intent-trace', number } });
                    assert.equal(failure.isError, true);
                    assert.ok(failure.content.some(item => item.type === 'text' && item.text.includes(message)));
                }
            } finally { await client.close(); }
        """.trimIndent()
        val process = ProcessBuilder("node", "--input-type=module", "-e", script).directory(Path.of("clients/zed").toFile())
            .redirectErrorStream(true).apply { environment()["INTENT_TRACE_SESSION_TOKEN"] = session() }.start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "MCP SDK 조회가 제한 시간 안에 끝나야 합니다.")
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private fun session(channel: SessionChannel = SessionChannel.CLIENT): String {
        val now = Instant.now()
        return sessions.issue(ActorIdentity.github(42, "lim"), GitHubUserOAuthTokens("ghu_context", now.plusSeconds(3600),
            "ghr_context", now.plusSeconds(7200)), channel).sessionToken
    }

    private fun link(body: String, label: String): String = HtmlUtils.htmlUnescape(
        Regex("href=\"([^\"]+)\">$label</a>").find(body)!!.groupValues[1],
    )

    @TestConfiguration
    class Configuration {
        @Bean @Primary fun contextGateway() = FakeContextGateway()
        @Bean @Primary fun access() = object : GitHubUserAccessGateway {
            override fun authenticate(accessToken: String) = ActorIdentity.github(42, "lim")
            override fun repositoryRole(accessToken: String, actor: ActorIdentity, repository: GitHubRepository) =
                RepositoryRole.READER.takeIf { repository.key == "acme/intent-trace" }
        }
    }

    class FakeContextGateway : GitHubContextGateway {
        val calls = AtomicInteger()
        override fun request(repository: GitHubRepository, number: Int): GitHubRequestContent {
            calls.incrementAndGet()
            when (number) {
                403 -> throw GitHubContextPermissionException()
                404 -> throw GitHubContextNotFoundException()
                429 -> throw GitHubRateLimitException(12)
                502 -> throw GitHubApiException("GitHub 자료를 조회하지 못했습니다.")
            }
            val issue = number == 8
            return GitHubRequestContent(if (issue) GitHubRequestKind.ISSUE else GitHubRequestKind.PULL_REQUEST, "<script>자료 확인</script>",
                "secret=remote-secret\n/Users/owner/project\n" + "검토할 내용 ".repeat(400),
                "https://github.com/${repository.key}/${if (issue) "issues" else "pull"}/$number", Instant.parse("2026-09-07T00:00:00Z"))
        }
        override fun actions(repository: GitHubRepository, revision: String, page: Int): GitHubActionsPage {
            calls.incrementAndGet()
            val now = Instant.parse("2026-09-07T00:00:00Z")
            return GitHubActionsPage(1001, List(20) { index ->
                val id = (page - 1) * 20 + index + 1L
                val status = when (index) { 1 -> "queued"; 2 -> "in_progress"; 4 -> "future_status"; else -> "completed" }
                val conclusion = if (index in 1..4) null else "failure"
                GitHubActionsRun(id, 2, "서버 검증", revision, "pull_request", status, conclusion,
                    now, now, "https://github.com/${repository.key}/actions/runs/$id")
            })
        }
    }

    companion object { private val revision = "a".repeat(40) }
}
