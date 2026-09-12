package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.GitHubAuthorizationWebhookService
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.application.GitHubUserOAuthTokens
import io.intenttrace.identity.application.GitHubUserSessionStore
import io.intenttrace.identity.application.SessionChannel
import io.intenttrace.identity.application.UserSessionManagement
import io.intenttrace.identity.domain.ActorIdentity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:webhook-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.h2.console.enabled=false",
    "intent-trace.github.webhook-secret=webhook-test-secret",
])
@AutoConfigureMockMvc
class GitHubWebhookIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val sessions: GitHubUserSessionStore,
    @Autowired private val management: UserSessionManagement,
    @Autowired private val clock: Clock,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val authorization: GitHubAuthorizationWebhookService,
) {
    @BeforeEach
    fun clearSessions() {
        management.revokeAll("github:42")
        management.revokeAll("github:43")
    }

    @Test
    fun `공식 GitHub HMAC 예제와 변조된 본문을 검증한다`() {
        val signature = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"
        assertTrue(GitHubWebhookSignature.matches("It's a Secret to Everybody", "Hello, World!".toByteArray(), signature))
        assertFalse(GitHubWebhookSignature.matches("It's a Secret to Everybody", "Hello, World?".toByteArray(), signature))
    }

    @Test
    fun `서명한 승인 취소는 숫자 사용자 ID의 브라우저와 도구 세션만 폐기한다`() {
        val client = issue(42, SessionChannel.CLIENT)
        val browser = issue(42, SessionChannel.BROWSER)
        issue(43, SessionChannel.CLIENT)

        repeat(2) {
            send(revoked).andExpect { status { isNoContent() }; content { string("") } }
        }

        assertTrue(management.list("github:42").isEmpty())
        assertEquals(1, management.list("github:43").size)
        assertFailsWith<GitHubUserAuthenticationException> { sessions.resolve(browser) }
        mvc.get("/api/v1/me/sessions") { header("Authorization", "Bearer $client") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `서명 누락과 변조와 잘못된 사용자 ID는 세션을 폐기하지 않는다`() {
        issue(42, SessionChannel.CLIENT)
        for (signature in listOf(null, "sha1=${"a".repeat(40)}", "sha256=${"z".repeat(64)}", sign(revoked + " "))) {
            send(revoked, signature = signature).andExpect { status { isUnauthorized() } }
        }
        for (body in listOf("{", "", """{"action":"revoked","sender":{"id":"42"}}""",
            """{"action":"revoked","sender":{"id":42.5}}""", """{"action":"revoked","sender":{"id":-1}}""",
            """{"action":"revoked"}""")) {
            send(body).andExpect { status { isBadRequest() }; content { string("") } }
        }
        assertEquals(1, management.list("github:42").size)
    }

    @Test
    fun `ping과 지원하지 않는 이벤트는 서명만 확인하고 세션을 유지한다`() {
        issue(42, SessionChannel.CLIENT)
        for (event in listOf("ping", "installation", "push")) {
            send(revoked, event = event).andExpect { status { isNoContent() } }
        }
        send("""{"action":"unknown","sender":{"id":42}}""").andExpect { status { isNoContent() } }
        assertEquals(1, management.list("github:42").size)
    }

    @Test
    fun `본문 상한과 비밀값 미설정은 수신을 거부한다`() {
        issue(42, SessionChannel.CLIENT)
        send(" ".repeat(1_048_577)).andExpect { status { isPayloadTooLarge() } }
        val disabled = GitHubWebhookController(GitHubProperties(), mapper, authorization)
        val request = MockHttpServletRequest("POST", "/webhooks/github").apply {
            setContent(revoked.toByteArray())
            addHeader("X-Hub-Signature-256", sign(revoked))
            addHeader("X-GitHub-Event", "github_app_authorization")
        }
        assertEquals(503, disabled.receive(request).statusCode.value())
        assertEquals(1, management.list("github:42").size)
    }

    private fun issue(id: Long, channel: SessionChannel): String = sessions.issue(
        ActorIdentity.github(id, "user$id"),
        GitHubUserOAuthTokens("ghu_webhook-$id", clock.instant().plusSeconds(3600),
            "ghr_webhook-$id", clock.instant().plusSeconds(86400)),
        channel,
    ).sessionToken

    private fun send(body: String, event: String = "github_app_authorization", signature: String? = sign(body)) =
        mvc.post("/webhooks/github") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("X-GitHub-Event", event)
            signature?.let { header("X-Hub-Signature-256", it) }
        }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("webhook-test-secret".toByteArray(), "HmacSHA256"))
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.toByteArray()))
    }

    private val revoked = """{"action":"revoked","sender":{"id":42,"login":"untrusted-display-name"}}"""
}
