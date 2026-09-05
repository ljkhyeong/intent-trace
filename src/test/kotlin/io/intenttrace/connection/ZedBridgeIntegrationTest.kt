package io.intenttrace.connection

import io.intenttrace.IntentTraceApplication
import io.intenttrace.identity.adapter.`in`.web.AuthenticatedMcpIntegrationTest
import io.intenttrace.identity.application.GitHubUserOAuthTokens
import io.intenttrace.identity.application.GitHubUserSessionStore
import io.intenttrace.identity.domain.ActorIdentity
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [IntentTraceApplication::class, AuthenticatedMcpIntegrationTest.AuthenticationTestConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.datasource.url=jdbc:h2:mem:zed-bridge;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "spring.h2.console.enabled=false", "server.shutdown=immediate"],
)
class ZedBridgeIntegrationTest(
    @Autowired private val sessions: GitHubUserSessionStore,
    @LocalServerPort private val port: Int,
) {
    @Test
    fun `Zed와 같은 stdio 연결로 실제 서버를 인증하고 도구 목록과 진단을 호출한다`() {
        assumeTrue(Files.exists(Path.of("clients/zed/node_modules/@modelcontextprotocol/sdk")), "Zed 검증에는 npm ci --prefix clients/zed --ignore-scripts가 필요합니다.")
        val now = Instant.now()
        val session = sessions.issue(ActorIdentity.github(42, "lim"), GitHubUserOAuthTokens(
            "ghu_zed-test", now.plusSeconds(3600), "ghr_zed-test", now.plusSeconds(7200),
        ))
        val process = ProcessBuilder("node", "clients/zed/intent-trace.mjs", "check", "http://127.0.0.1:$port/mcp", "acme/intent-trace")
            .redirectErrorStream(true).apply { environment()["INTENT_TRACE_SESSION_TOKEN"] = session.sessionToken }.start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        assertTrue(finished, "Zed 연결 점검이 30초 안에 끝나야 합니다.")
        val output = process.inputStream.bufferedReader().readText()
        assertFalse(output.contains(session.sessionToken), "세션은 출력하지 않아야 합니다.")
        assertEquals(0, process.exitValue(), output)
        assertTrue(output.contains("MCP 연결 성공"), output)
        assertTrue(output.contains("repository_read: VERIFIED"), output)
    }
}
