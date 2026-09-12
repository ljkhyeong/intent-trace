package io.intenttrace.connection

import io.intenttrace.IntentTraceApplication
import io.intenttrace.identity.adapter.`in`.web.AuthenticatedMcpIntegrationTest
import io.intenttrace.identity.application.GitHubUserOAuthTokens
import io.intenttrace.identity.application.GitHubUserSessionStore
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.publication.application.GitHubPullRequestReader
import io.intenttrace.publication.application.PullRequestSnapshot
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.application.GitEvidenceGateway
import io.intenttrace.record.application.GitEvidenceSnapshot
import io.intenttrace.record.application.ChangeRecordFacade
import io.intenttrace.record.application.CreateChangeRecordCommand
import io.intenttrace.record.application.ConfirmChangeRecordCommand
import io.intenttrace.record.application.EvidenceUnavailableException
import io.intenttrace.record.application.EvidenceUnavailableReason
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [IntentTraceApplication::class, AuthenticatedMcpIntegrationTest.AuthenticationTestConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.datasource.url=jdbc:h2:mem:zed-bridge;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "spring.h2.console.enabled=false", "server.shutdown=immediate"],
)
class ZedBridgeIntegrationTest(
    @Autowired private val sessions: GitHubUserSessionStore,
    @Autowired private val records: ChangeRecordFacade,
    @LocalServerPort private val port: Int,
) {
    @MockitoBean
    private lateinit var evidence: GitEvidenceGateway

    @MockitoBean
    private lateinit var pullRequests: GitHubPullRequestReader

    @Test
    fun `Zed와 같은 stdio 연결로 실제 서버를 인증하고 도구 목록과 진단을 호출한다`() {
        val output = check()
        assertTrue(output.contains("MCP 연결 성공"), output)
        assertTrue(output.contains("repository_read: VERIFIED"), output)
        Mockito.verifyNoInteractions(evidence, pullRequests)
    }

    @Test
    fun `연결 점검은 실패 사유와 나머지 진단 안내를 함께 표시한다`() {
        val repository = GitHubRepository.parse("acme/intent-trace")
        val revision = "d".repeat(40)
        for (reason in EvidenceUnavailableReason.entries) {
            Mockito.doThrow(EvidenceUnavailableException(reason)).`when`(evidence).snapshot(repository, revision)
            val output = check("--revision", revision, expectedExitCode = 1)
            assertTrue(output.contains("git_tree_read: FAILED — ${reason.message}"), output)
            assertTrue(output.contains("repository_read: VERIFIED — GitHub 응답으로 확인했습니다."), output)
            assertTrue(output.contains("publication_credentials: NOT_CONFIGURED — 운영자가 서버 게시용 GitHub App client ID와 private key를 설정해야 합니다."), output)
        }
    }

    @Test
    fun `세션 종료는 잘못된 ID를 노출하거나 다른 연결을 종료하지 않고 ID 생략만 현재 연결을 종료한다`() {
        val now = Instant.now()
        fun issue() = sessions.issue(ActorIdentity.github(42, "lim"), GitHubUserOAuthTokens(
            "ghu_zed-session-test", now.plusSeconds(3600), "ghr_zed-session-test", now.plusSeconds(7200),
        ))
        val current = issue()
        val other = issue()
        val otherId = sessions.resolve(other.sessionToken).sessionId
        val script = """
            import assert from 'node:assert/strict';
            import { Client } from '@modelcontextprotocol/sdk/client/index.js';
            import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
            const client = new Client({ name: 'session-revocation-test', version: '1' });
            const transport = new StdioClientTransport({ command: process.execPath,
                args: ['intent-trace.mjs', 'serve', 'http://127.0.0.1:$port/mcp'],
                env: { INTENT_TRACE_SESSION_TOKEN: process.env.INTENT_TRACE_SESSION_TOKEN }, stderr: 'pipe' });
            transport.stderr?.resume();
            const call = (name, args = {}) => client.callTool({ name, arguments: args });
            const data = result => {
                assert.notEqual(result.isError, true);
                return result.structuredContent ?? JSON.parse(result.content.find(item => item.type === 'text').text);
            };
            const ids = async () => data(await call('list_my_sessions')).sessions.map(item => item.id).sort();
            try {
                await client.connect(transport);
                const before = await ids();
                for (const sessionId of ['its_' + 'x'.repeat(43), 'ghu_private-marker', '/Users/example/private', '']) {
                    const result = await call('revoke_my_session', { sessionId });
                    assert.equal(result.isError, true);
                    assert.ok(JSON.stringify(result).includes('연결 ID는 UUID 형식이어야 합니다.'));
                    if (sessionId) assert.ok(!JSON.stringify(result).includes(sessionId));
                    assert.deepEqual(await ids(), before);
                }
                assert.equal(data(await call('revoke_my_session', { sessionId: '$otherId' })).revokedCount, 1);
                assert.equal(data(await call('revoke_my_session', { sessionId: '$otherId' })).revokedCount, 0);
                assert.deepEqual(await ids(), before.filter(id => id !== '$otherId'));
                assert.equal(data(await call('revoke_my_session')).revokedCount, 1);
            } finally { await client.close(); }
        """.trimIndent()
        val process = ProcessBuilder("node", "--input-type=module", "-e", script).directory(Path.of("clients/zed").toFile())
            .redirectErrorStream(true).apply { environment()["INTENT_TRACE_SESSION_TOKEN"] = current.sessionToken }.start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        assertTrue(finished, "세션 종료 검증이 30초 안에 끝나야 합니다.")
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
        assertFailsWith<GitHubUserAuthenticationException> { sessions.resolve(current.sessionToken) }
        assertFailsWith<GitHubUserAuthenticationException> { sessions.resolve(other.sessionToken) }
    }

    @Test
    fun `코드 확인 불가는 REST와 MCP에서 사유를 유지하고 연결 진단에서도 권한 오류와 구분한다`() {
        val repository = GitHubRepository.parse("acme/intent-trace")
        val actor = ActorIdentity.github(42, "lim")
        val cases = listOf(
            EvidenceUnavailableReason.SIZE_LIMIT to "파일 또는 응답이 지원 크기를 초과했습니다.",
            EvidenceUnavailableReason.TRUNCATED_TREE to "GitHub에서 전체 파일 트리를 받지 못했습니다.",
            EvidenceUnavailableReason.UNSUPPORTED_OBJECT to "현재 지원하지 않는 Git 객체입니다.",
        ).mapIndexed { index, (reason, message) ->
            val revision = (index + 1).toString().repeat(40)
            Mockito.`when`(evidence.snapshot(repository, revision)).thenThrow(EvidenceUnavailableException(reason))
            val draft = records.create(CreateChangeRecordCommand(
                UUID.randomUUID().toString(), repository.key, null, "a".repeat(64), "코드 확인 불가", "확인 불가 사유를 구분한다.",
                listOf(Decision("원격 코드 확인", null, PurposeSource.STATED_BY_USER)),
                listOf(CodeAnchor("sample.kt", null, 1, 1, "b".repeat(64))), emptyList(), emptyList(),
            ), actor)
            records.confirm(ConfirmChangeRecordCommand(draft.id, draft.version, revision, draft.snapshotDigest), actor)
            "['${draft.id}', '$revision', '${reason.name}', '$message']"
        }
        val now = Instant.now()
        val session = sessions.issue(actor, GitHubUserOAuthTokens(
            "ghu_evidence-test", now.plusSeconds(3600), "ghr_evidence-test", now.plusSeconds(7200),
        ))
        val script = """
            import assert from 'node:assert/strict';
            import { Client } from '@modelcontextprotocol/sdk/client/index.js';
            import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
            const client = new Client({ name: 'evidence-unavailable-test', version: '1' });
            const transport = new StdioClientTransport({ command: process.execPath,
                args: ['intent-trace.mjs', 'serve', 'http://127.0.0.1:$port/mcp'],
                env: { INTENT_TRACE_SESSION_TOKEN: process.env.INTENT_TRACE_SESSION_TOKEN }, stderr: 'pipe' });
            transport.stderr?.resume();
            const get = path => fetch('http://127.0.0.1:$port' + path, {
                headers: { Authorization: 'Bearer ' + process.env.INTENT_TRACE_SESSION_TOKEN } });
            const data = result => result.structuredContent ?? JSON.parse(result.content.find(item => item.type === 'text').text);
            const diagnosis = (result, message) => {
                const check = result.checks.find(item => item.name === 'git_tree_read');
                assert.equal(check.status, 'FAILED');
                assert.equal(check.message, message);
                assert.ok(result.checks.some(item => item.name === 'publication_credentials'));
            };
            try {
                await client.connect(transport);
                for (const [id, revision, reason, message] of [${cases.joinToString(",")}]) {
                    const rest = await get('/api/v1/change-records/' + id + '/evidence-check');
                    assert.equal(rest.status, 422);
                    const problem = await rest.json();
                    assert.equal(problem.code, 'EVIDENCE_UNAVAILABLE');
                    assert.equal(problem.reason, reason);
                    assert.equal(problem.detail, message);
                    const mcp = await client.callTool({ name: 'check_change_record_evidence', arguments: { recordId: id } });
                    assert.equal(mcp.isError, true);
                    assert.ok(JSON.stringify(mcp).includes(message));
                    assert.ok(JSON.stringify(mcp).includes(reason));
                    const restDiagnosis = await get('/api/v1/connection-diagnostics?repositoryKey=acme/intent-trace&revision=' + revision);
                    assert.equal(restDiagnosis.status, 200);
                    diagnosis(await restDiagnosis.json(), message);
                    const mcpDiagnosis = await client.callTool({ name: 'diagnose_connection', arguments: { repositoryKey: 'acme/intent-trace', revision } });
                    assert.notEqual(mcpDiagnosis.isError, true);
                    diagnosis(data(mcpDiagnosis), message);
                }
            } finally { await client.close(); }
        """.trimIndent()
        val process = ProcessBuilder("node", "--input-type=module", "-e", script).directory(Path.of("clients/zed").toFile())
            .redirectErrorStream(true).apply { environment()["INTENT_TRACE_SESSION_TOKEN"] = session.sessionToken }.start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        assertTrue(finished, "코드 확인 불가 검증이 30초 안에 끝나야 합니다.")
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    @Test
    fun `연결 점검은 PR 커밋 일치를 구분하고 지정한 커밋 또는 PR HEAD를 확인한다`() {
        val target = GitHubPullRequestTarget("acme", "intent-trace", 12)
        val repository = GitHubRepository.parse("acme/intent-trace")
        val head = "b".repeat(40)
        val explicit = "c".repeat(64)
        Mockito.`when`(pullRequests.read(target)).thenReturn(PullRequestSnapshot(head, false))
        for ((options, revision) in listOf(
            listOf("--pr", "12") to head,
            listOf("--pr", "12", "--revision", head.uppercase()) to head,
            listOf("--pr", "12", "--revision", explicit) to explicit,
            listOf("--revision", head) to head,
        )) {
            Mockito.`when`(evidence.snapshot(repository, revision)).thenReturn(GitEvidenceSnapshot(emptyMap()))
            for (explicitAddress in listOf(true, false)) {
                Mockito.clearInvocations(evidence, pullRequests)
                val mismatch = "--pr" in options && revision != head
                val output = check(*options.toTypedArray(), explicitAddress = explicitAddress, expectedExitCode = if (mismatch) 1 else 0)
                assertTrue(output.contains("git_tree_read: VERIFIED"), output)
                if ("--pr" in options && "--revision" in options) {
                    val expected = if (mismatch) "FAILED — 입력한 커밋이 PR의 현재 커밋과 다릅니다. PR의 최신 커밋으로 확인한 기록만 게시할 수 있습니다."
                        else "VERIFIED — 입력한 커밋이 PR의 현재 커밋과 같습니다."
                    assertTrue(output.contains("pull_request_revision: $expected"), output)
                } else assertFalse(output.contains("pull_request_revision:"), output)
                Mockito.verify(evidence).snapshot(repository, revision)
                if ("--pr" in options) {
                    assertTrue(output.contains("pull_request_read: VERIFIED"), output)
                    Mockito.verify(pullRequests).read(target)
                } else Mockito.verifyNoInteractions(pullRequests)
            }
        }
    }

    private fun check(vararg options: String, explicitAddress: Boolean = true, expectedExitCode: Int = 0): String {
        assumeTrue(Files.exists(Path.of("clients/zed/node_modules/@modelcontextprotocol/sdk")), "Zed 검증에는 npm ci --prefix clients/zed --ignore-scripts가 필요합니다.")
        val now = Instant.now()
        val session = sessions.issue(ActorIdentity.github(42, "lim"), GitHubUserOAuthTokens(
            "ghu_zed-test", now.plusSeconds(3600), "ghr_zed-test", now.plusSeconds(7200),
        ))
        val address = "http://127.0.0.1:$port/mcp"
        val command = listOf("node", "clients/zed/intent-trace.mjs", "check") +
            (if (explicitAddress) listOf(address) else emptyList()) + listOf("acme/intent-trace", *options)
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["INTENT_TRACE_SESSION_TOKEN"] = session.sessionToken
            environment()["INTENT_TRACE_MCP_URL"] = if (explicitAddress) "invalid-address" else address
        }.start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        assertTrue(finished, "Zed 연결 점검이 30초 안에 끝나야 합니다.")
        val output = process.inputStream.bufferedReader().readText()
        assertFalse(output.contains(session.sessionToken), "세션은 출력하지 않아야 합니다.")
        assertEquals(expectedExitCode, process.exitValue(), output)
        return output
    }
}
