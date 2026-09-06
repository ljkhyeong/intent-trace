package io.intenttrace.record.application

import io.intenttrace.IntentTraceApplication
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
    fun `REST는 정제한 초안 재료만 반환하고 권한 없는 요청과 쓰기 요청을 거부한다`() {
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
            content { string(containsString("실행 시도 2")) }; content { string(containsString("&lt;script&gt;")) }
        }.andReturn().response.contentAsString
        assertFalse(page.contains("<script>")); assertFalse(page.contains("remote-secret"))
        mvc.get("/api/v1/github/request-context?repositoryKey=acme/intent-trace&number=7") { cookie(cookie) }
            .andExpect { status { isUnauthorized() } }
        val directory = Path.of("build/reports/github-context")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("github.html"), page.replace("/assets/record-browser.css", "record-browser.css"))
        Files.copy(Path.of("src/main/resources/static/assets/record-browser.css"), directory.resolve("record-browser.css"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
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
            return GitHubRequestContent(GitHubRequestKind.PULL_REQUEST, "<script>자료 확인</script>",
                "secret=remote-secret\n/Users/owner/project\n" + "검토할 내용 ".repeat(400),
                "https://github.com/${repository.key}/pull/$number", Instant.parse("2026-09-07T00:00:00Z"))
        }
        override fun actions(repository: GitHubRepository, revision: String, page: Int): GitHubActionsPage {
            calls.incrementAndGet()
            val now = Instant.parse("2026-09-07T00:00:00Z")
            return GitHubActionsPage(1001, List(20) { index -> GitHubActionsRun(index + 1L, 2, "서버 검증", revision,
                "pull_request", "completed", "failure", now, now, "https://github.com/${repository.key}/actions/runs/${index + 1}") })
        }
    }

    companion object { private val revision = "a".repeat(40) }
}
