package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.publication.application.*
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import io.intenttrace.IntentTraceApplication
import io.intenttrace.identity.adapter.`in`.web.BROWSER_SESSION_COOKIE
import io.intenttrace.identity.adapter.`in`.web.GitHubOAuthController
import io.intenttrace.identity.adapter.`in`.web.GitHubOAuthSessionIntegrationTest
import io.intenttrace.identity.application.BrowserReturnPath
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.ChangeRecordFacade
import io.intenttrace.record.application.ConfirmChangeRecordCommand
import io.intenttrace.record.application.CreateChangeRecordCommand
import io.intenttrace.record.application.PublishChangeRecordCommand
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [IntentTraceApplication::class, GitHubOAuthSessionIntegrationTest.OAuthTestConfiguration::class, RecordBrowserIntegrationTest.Configuration::class],
    properties = [
        "spring.datasource.url=jdbc:h2:mem:record-browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "intent-trace.github.user-authorization.callback-url=http://127.0.0.1:8080/auth/github/callback",
    ],
)
@AutoConfigureMockMvc
class RecordBrowserIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val records: ChangeRecordFacade, @Autowired private val tracking: GitHubPublicationTracking) {
    @Test
    fun `기록 링크는 로그인 후 원래 기록으로 돌아오고 브라우저 세션은 API에서 사용할 수 없다`() {
        val actor = ActorIdentity.github(42, "lim")
        val draft = records.create(command("브라우저 <script>alert(1)</script>"), actor)
        val confirmed = records.confirm(ConfirmChangeRecordCommand(draft.id, draft.version, "b".repeat(40), digest), actor)
        records.publish(PublishChangeRecordCommand(draft.id, confirmed.version, digest), actor)
        val path = "/records/${draft.id}"
        val anonymous = mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString
        preview("login", anonymous)
        assertTrue(anonymous.contains("GitHub로 로그인"))
        assertFalse(anonymous.contains(draft.title))
        val cookie = login(path)
        val displayed = mvc.get(path) { cookie(cookie) }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.CACHE_CONTROL, containsString("no-store")) }
            header { string("Content-Security-Policy", containsString("default-src 'none'")) }
            content { string(containsString("&lt;script&gt;")) }
            content { string(containsString("판단과 근거")) }
        }.andReturn().response.contentAsString
        preview("record", displayed)
        val search = mvc.get("/records") { cookie(cookie); param("repositoryKey", "acme/browser"); param("q", "브라우저") }.andExpect {
            status { isOk() }; content { string(containsString(draft.id.toString())) }
        }.andReturn().response.contentAsString
        preview("search", search)
        mvc.get("/api/v1/change-records/${draft.id}") { cookie(cookie) }.andExpect { status { isUnauthorized() } }
        mvc.get("/api/v1/change-records/${draft.id}") { header(HttpHeaders.AUTHORIZATION, "Bearer ${cookie.value}") }.andExpect { status { isUnauthorized() } }
        mvc.post("/records/logout") { cookie(cookie); header(HttpHeaders.ORIGIN, "https://another.example") }.andExpect { status { isForbidden() } }
        mvc.post("/records/logout") { cookie(cookie); header(HttpHeaders.ORIGIN, "http://127.0.0.1:8080") }.andExpect {
            status { isSeeOther() }; header { string(HttpHeaders.LOCATION, "/records") }
        }
        mvc.get(path) { cookie(cookie) }.andExpect { status { isOk() }; content { string(containsString("연결이 만료됐습니다")) } }
    }

    @Test
    fun `다른 작성자의 초안은 브라우저 검색과 단건 조회에 노출하지 않는다`() {
        val draft = records.create(command("브라우저 비공개 내용"), ActorIdentity.github(99, "other"))
        val cookie = login("/records?repositoryKey=acme%2Fbrowser&scope=MINE&q=비공개")
        mvc.get("/records/${draft.id}") { cookie(cookie) }.andExpect {
            status { isNotFound() }; content { string(containsString("기록이 없거나 열람 권한이 없습니다")) }
        }
        val search = mvc.get("/records") { cookie(cookie); param("repositoryKey", "acme/browser"); param("scope", "MINE"); param("q", "비공개") }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertFalse(search.contains(draft.id.toString()))
        assertFalse(search.contains(draft.title))
    }

    @Test
    fun `로그인 복귀 주소는 기록 화면만 허용한다`() {
        listOf("https://evil.example/records", "//evil.example/records", "/records/../auth/github/start", "/records%2flogout", "/records/logout", "/records#other").forEach {
            assertFailsWith<IllegalArgumentException> { BrowserReturnPath.validate(it) }
        }
        mvc.get("/auth/github/start") { param("returnTo", "https://evil.example/records") }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `브라우저에서 PR 게시 미확인과 연결 진단 및 원본 비교를 읽고 다른 작성자의 비교는 숨긴다`() {
        val actor = ActorIdentity.github(42, "lim")
        val originalDraft = records.create(command("원본 판단"), actor)
        val confirmed = records.confirm(ConfirmChangeRecordCommand(originalDraft.id, originalDraft.version, "b".repeat(40), digest), actor)
        val original = records.publish(PublishChangeRecordCommand(originalDraft.id, confirmed.version, digest), actor)
        val successor = records.create(command("후속 판단").copy(derivedFromRecordId = original.id,
            decisions = listOf(Decision("공개 본문을 보존한다.", "작성자가 확인한 내용을 유지한다.", PurposeSource.CONFIRMED_AI_SUMMARY)),
            codeAnchors = listOf(CodeAnchor("src/New.kt", null, 2, 3, "d".repeat(64)))), actor)
        val target = GitHubPullRequestTarget("acme", "browser", 12)
        val attempt = tracking.start(original.id, target, PublicationOperation.PUBLISH)
        tracking.finish(attempt, PublicationAttemptStatus.RESULT_UNKNOWN, "UNKNOWN", null)
        val cookie = login("/records/pull-requests?repositoryKey=acme%2Fbrowser&pullNumber=12")
        val overview = mvc.get("/records/pull-requests") { cookie(cookie); param("repositoryKey", "acme/browser"); param("pullNumber", "12") }
            .andExpect { status { isOk() }; content { string(containsString("게시 결과 미확인")) }; content { string(containsString("이전 커밋의 기록")) } }.andReturn().response.contentAsString
        preview("pull-requests", overview)
        val connectionCookie = login("/records/connection?repositoryKey=acme%2Fbrowser")
        val diagnosis = mvc.get("/records/connection") { cookie(connectionCookie); param("repositoryKey", "acme/browser"); param("pullNumber", ""); param("revision", "") }
            .andExpect { status { isOk() }; content { string(containsString("저장소 읽기")) }; content { string(containsString("확인 완료")) } }.andReturn().response.contentAsString
        preview("connection", diagnosis)
        val comparisonCookie = login("/records/${successor.id}/comparison")
        val compared = mvc.get("/records/${successor.id}/comparison") { cookie(comparisonCookie) }
            .andExpect { status { isOk() }; content { string(containsString("후속 기록에 제출된 검증이 없습니다")) }; content { string(containsString("src/App.kt")) }; content { string(containsString("src/New.kt")) } }.andReturn().response.contentAsString
        preview("comparison", compared)
        mvc.get("/api/v1/change-records/${successor.id}/comparison") { header(HttpHeaders.AUTHORIZATION, "Bearer ghu_browser-test") }.andExpect {
            status { isOk() }
            jsonPath("$.changedFields[1]") { value("DECISIONS") }
            jsonPath("$.original.id") { value(original.id.toString()) }
            jsonPath("$.successor.content.verifications.length()") { value(0) }
        }
        val hidden = records.create(command("다른 사람의 후속 초안").copy(derivedFromRecordId = original.id), ActorIdentity.github(99, "other"))
        mvc.get("/records/${hidden.id}/comparison") { cookie(cookie) }.andExpect { status { isNotFound() } }
        assertEquals(original, records.get(original.id))
    }

    @TestConfiguration
    class Configuration {
        @Bean @Primary fun pullRequestReader() = object : GitHubPullRequestReader {
            override fun read(target: GitHubPullRequestTarget) = PullRequestSnapshot("c".repeat(40), false)
        }
    }

    private fun login(returnTo: String): Cookie {
        val start = mvc.get("/auth/github/start") { param("returnTo", returnTo) }.andExpect { status { isFound() } }.andReturn()
        val state = UriComponentsBuilder.fromUriString(start.response.getHeader(HttpHeaders.LOCATION)!!).build().queryParams.getFirst("state")!!
        val stateCookie = start.response.cookies.single { it.name == GitHubOAuthController.STATE_COOKIE }
        val callback = mvc.get("/auth/github/callback") {
            cookie(stateCookie); param("state", state); param("code", "authorization-code")
            param("returnTo", "https://evil.example")
        }.andExpect { status { isSeeOther() }; header { string(HttpHeaders.LOCATION, URI(returnTo).toASCIIString()) } }.andReturn().response
        assertFalse(callback.contentAsString.contains("its_"))
        assertFalse(callback.contentAsString.contains("ghu_"))
        val cookie = callback.cookies.single { it.name == BROWSER_SESSION_COOKIE }
        assertTrue(cookie.isHttpOnly)
        assertEquals("/records", cookie.path)
        assertEquals(28800, cookie.maxAge)
        assertTrue(callback.getHeaders(HttpHeaders.SET_COOKIE).any { it.contains("SameSite=Lax") })
        return cookie
    }

    private fun preview(name: String, content: String) {
        val directory = Files.createDirectories(Path.of("build/browser-preview"))
        Files.writeString(directory.resolve("$name.html"), content)
    }

    private fun command(title: String) = CreateChangeRecordCommand(
        UUID.randomUUID().toString(), "acme/browser", null, digest, title, "브라우저에서 요청과 검증을 읽는다.",
        listOf(Decision("공개 본문을 보존한다.", "작성자가 확인한 내용을 유지한다.", PurposeSource.STATED_BY_USER)),
        listOf(CodeAnchor("src/App.kt", null, 1, 2, "c".repeat(64))), emptyList(), emptyList(),
    )
    companion object { private val digest = "a".repeat(64) }
}
