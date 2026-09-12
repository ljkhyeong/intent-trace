package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.config.GitHubRateLimitException
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
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.ChangeRecordFacade
import io.intenttrace.record.application.ConfirmChangeRecordCommand
import io.intenttrace.record.application.CreateChangeRecordCommand
import io.intenttrace.record.application.PublishChangeRecordCommand
import io.intenttrace.record.application.*
import io.intenttrace.identity.application.GitHubUserSessionStore
import io.intenttrace.identity.application.UserSessionManagement
import io.intenttrace.identity.application.GitHubUserOAuthTokens
import io.intenttrace.identity.domain.GitHubRepository
import java.time.Instant
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
import org.springframework.web.util.HtmlUtils
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
class RecordBrowserIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val records: ChangeRecordFacade,
    @Autowired private val tracking: GitHubPublicationTracking, @Autowired private val sessionStore: GitHubUserSessionStore,
    @Autowired private val sessionManagement: UserSessionManagement,
    @Autowired private val userAccess: GitHubOAuthSessionIntegrationTest.TestGitHubUserAccessGateway) {
    @Test
    fun `일시 장애 후 같은 세션과 검색 조건으로 목록과 기록을 다시 조회한다`() {
        val record = records.create(command("조회 복구"), ActorIdentity.github(42, "lim"))
        val searchUrl = URI(url("/records", "repositoryKey" to "acme/browser", "scope" to "MINE",
            "q" to "요청 & + % \"<입력>\"", "status" to "DRAFT", "path" to "src/App.kt"))
        val cookie = login(searchUrl.toString())
        for (target in listOf(searchUrl, URI("/records/${record.id}?${searchUrl.rawQuery}"))) {
            userAccess.authenticationFailure = GitHubIdentityApiException("테스트 사용자 조회 장애")
            val failed = try {
                mvc.get(target) { cookie(cookie) }.andExpect {
                    status { isBadGateway() }
                    header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
                    header { string("Referrer-Policy", "no-referrer") }
                }.andReturn().response.contentAsString
            } finally {
                userAccess.authenticationFailure = null
            }
            assertEquals(target, link(failed, "다시 조회"))
            assertFalse(failed.contains(cookie.value))
            assertFalse(failed.contains("테스트 사용자 조회 장애"))
            mvc.get(link(failed, "다시 조회")) { cookie(cookie) }.andExpect {
                status { isOk() }; content { string(containsString("로그아웃</button>")) }
            }
            preview("retry-query", failed)
        }
    }

    @Test
    fun `연결 종료 중 장애가 나면 종료 요청의 재조회 링크를 만들지 않는다`() {
        val cookie = login("/records/sessions")
        val sessionId = sessionStore.resolve(cookie.value).sessionId!!
        userAccess.authenticationFailure = GitHubIdentityApiException("테스트 사용자 조회 장애")
        try {
            for (target in listOf("/records/sessions/$sessionId/revoke", "/records/sessions/revoke-all")) {
                val failed = mvc.post(target) { cookie(cookie); header(HttpHeaders.ORIGIN, "http://127.0.0.1:8080") }
                    .andExpect { status { isBadGateway() } }.andReturn().response.contentAsString
                assertFalse(failed.contains("다시 조회"))
                assertEquals(URI("/records"), link(failed, "기록 찾기로 이동"))
            }
        } finally {
            userAccess.authenticationFailure = null
        }
        assertEquals(sessionId, sessionStore.resolve(cookie.value).sessionId)
    }

    @Test
    fun `웹 연결 목록은 본인 연결만 보이고 동일 출처에서 선택 및 전체 종료한다`() {
        val actor = ActorIdentity.github(42, "lim")
        val now = Instant.now()
        fun issue(owner: ActorIdentity) = sessionStore.issue(owner, GitHubUserOAuthTokens("ghu_browser-session", now.plusSeconds(7200), "ghr_browser-session", now.plusSeconds(14400)))
        val client = issue(actor)
        val clientId = sessionStore.resolve(client.sessionToken).sessionId!!
        val other = ActorIdentity.github(99, "other")
        issue(other)
        val otherId = sessionManagement.list(other.subject).first().id
        val cookie = login("/records/sessions")
        val currentId = sessionStore.resolve(cookie.value).sessionId!!
        val page = mvc.get("/records/sessions") { cookie(cookie) }.andExpect {
            status { isOk() }; content { string(containsString("현재 연결")) }; content { string(containsString(clientId.toString())) }
            content { string(containsString("내 모든 브라우저·Agent·API 연결이 종료됩니다.")) }
        }.andReturn().response.contentAsString
        preview("sessions", page)
        assertFalse(page.contains(otherId.toString()))
        assertFalse(page.contains(cookie.value)); assertFalse(page.contains(client.sessionToken)); assertFalse(page.contains("ghu_"))
        mvc.post("/records/sessions/$clientId/revoke") { cookie(cookie) }.andExpect { status { isForbidden() } }
        mvc.post("/records/sessions/$clientId/revoke") { cookie(cookie); header(HttpHeaders.ORIGIN, "https://another.example") }.andExpect { status { isForbidden() } }
        assertTrue(sessionManagement.list(actor.subject).any { it.id == clientId })
        mvc.post("/records/sessions/$otherId/revoke") { cookie(cookie); header(HttpHeaders.ORIGIN, "http://127.0.0.1:8080") }.andExpect { status { isSeeOther() } }
        assertTrue(sessionManagement.list(other.subject).any { it.id == otherId })
        mvc.post("/records/sessions/$clientId/revoke") { cookie(cookie); header(HttpHeaders.ORIGIN, "http://127.0.0.1:8080") }.andExpect {
            status { isSeeOther() }; header { string(HttpHeaders.LOCATION, "/records/sessions") }
        }
        assertFalse(sessionManagement.list(actor.subject).any { it.id == clientId })
        mvc.post("/records/sessions/$currentId/revoke") { cookie(cookie); header(HttpHeaders.ORIGIN, "http://127.0.0.1:8080") }.andExpect {
            status { isSeeOther() }; header { string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")) }
        }
        val renewed = login("/records/sessions")
        val anotherBrowser = login("/records/sessions")
        issue(actor)
        mvc.post("/records/sessions/revoke-all") { cookie(renewed); header(HttpHeaders.ORIGIN, "http://127.0.0.1:8080") }.andExpect { status { isSeeOther() } }
        assertTrue(sessionManagement.list(actor.subject).isEmpty())
        assertTrue(sessionManagement.list(other.subject).isNotEmpty())
        mvc.get("/records/sessions") { cookie(anotherBrowser) }.andExpect { content { string(containsString("연결이 만료됐습니다")) } }
        mvc.get("/records/sessions") { cookie(renewed) }.andExpect { content { string(containsString("연결이 만료됐습니다")) } }
    }

    @Test
    fun `웹 줄 조회의 부분 실패를 재조회하고 코드 확인과 이력은 접근 범위를 지킨다`() {
        val actor = ActorIdentity.github(42, "lim")
        val repository = "acme/history-browser"
        fun publish(revision: String, title: String): io.intenttrace.record.domain.ChangeRecord {
            val draft = records.create(command(title).copy(repositoryKey = repository, snapshotDigest = evidenceSnapshot.digest,
                codeAnchors = listOf(CodeAnchor("src/App.kt", null, 1, 2, GitEvidenceDigest.sha256(evidenceBytes)))), actor)
            val confirmed = records.confirm(ConfirmChangeRecordCommand(draft.id, draft.version, revision, evidenceSnapshot.digest), actor)
            return records.publish(PublishChangeRecordCommand(draft.id, confirmed.version, evidenceSnapshot.digest), actor)
        }
        val matched = publish("b".repeat(40), "줄 조회 성공")
        val failed = publish("f".repeat(40), "트리 확인 불가")
        repeat(4) { publish("b".repeat(40), "줄 조회 추가 $it") }
        val cookie = login("/records/history?repositoryKey=acme%2Fhistory-browser&revision=${"b".repeat(40)}&path=src%2FApp.kt&line=1")
        val page = mvc.get("/records/history") {
            cookie(cookie); param("repositoryKey", repository); param("revision", "b".repeat(40)); param("path", "src/App.kt"); param("line", "1")
        }.andExpect { status { isOk() }; content { string(containsString("커밋·줄 일치")) }; content { string(containsString("다음 기록 조회")) } }.andReturn().response.contentAsString
        preview("history", page)
        val retry = mvc.get("/records/history") {
            cookie(cookie); param("repositoryKey", repository); param("revision", "b".repeat(40)); param("path", "src/App.kt"); param("line", "1"); param("retryRecordId", failed.id.toString())
        }.andExpect { status { isOk() }; content { string(containsString("전체 파일 트리를 받지 못했습니다")) }; content { string(containsString("이 기록 다시 조회")) } }.andReturn().response.contentAsString
        preview("history-failure", retry)
        val retryUrl = link(retry, "이 기록 다시 조회")
        val retried = mvc.get(retryUrl) { cookie(cookie) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val failedDetail = mvc.get(link(retried, "기록 읽기")) { cookie(cookie) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals(retryUrl, link(failedDetail, "파일·줄 조회로 돌아가기"))
        val evidence = mvc.get("/records/${matched.id}/evidence") { cookie(cookie) }.andExpect {
            status { isOk() }; content { string(containsString("스냅샷 해시와 모든 관련 코드가 일치")) }; content { string(containsString("서버는 테스트 실행 여부를 확인하지 않습니다")) }
        }.andReturn().response.contentAsString
        preview("evidence", evidence)
        val failedRecordUrl = "/records/${failed.id}?repositoryKey=acme%2Fhistory-browser&scope=TEAM&path=src%2FApp.kt"
        val unavailable = mvc.get(URI(failedRecordUrl.replace("?", "/evidence?"))) { cookie(cookie) }.andExpect {
            status { isUnprocessableContent() }; content { string(containsString("전체 파일 트리를 받지 못했습니다")) }
            content { string(containsString("코드 일치 여부는 미확인")) }
            content { string(containsString("/records/${failed.id}")) }
        }.andReturn().response.contentAsString
        assertFalse(unavailable.contains("잠시 후 다시 시도"))
        assertEquals(URI(failedRecordUrl), link(unavailable, "기록으로 돌아가기"))
        preview("evidence-unavailable", unavailable)
        val stopped = publish("d".repeat(40), "조회 중단 기록")
        val paused = mvc.get("/records/history") {
            cookie(cookie); param("repositoryKey", repository); param("revision", "b".repeat(40)); param("path", "src/App.kt"); param("line", "1"); param("retryRecordId", stopped.id.toString())
        }.andExpect { status { isOk() }; content { string(containsString("원인 확인 후 다시 조회")) }; content { string(containsString("GitHub 호출 한도")) }; content { string(containsString("반복 조회 전에 관리자에게")) } }.andReturn().response.contentAsString
        assertFalse(paused.contains("중단 위치부터 계속 조회"))
        preview("history-stopped", paused)
        val activities = mvc.get("/records/${matched.id}/activities") { cookie(cookie) }.andExpect {
            status { isOk() }; content { string(containsString("작성자 확인")) }; content { string(containsString("초안 생성")) }
        }.andReturn().response.contentAsString
        preview("activities", activities)
        val hidden = records.create(command("숨긴 코드").copy(repositoryKey = repository), ActorIdentity.github(99, "other"))
        for (suffix in listOf("evidence", "activities")) mvc.get("/records/${hidden.id}/$suffix") { cookie(cookie) }.andExpect { status { isNotFound() } }
        mvc.get("/records/history") { cookie(cookie); param("repositoryKey", repository); param("revision", "b".repeat(40)); param("path", "src/App.kt"); param("line", "1"); param("retryRecordId", hidden.id.toString()) }.andExpect { status { isNotFound() } }
        mvc.get("/records/${matched.id}/evidence").andExpect { content { string(containsString("GitHub로 로그인")) } }
    }
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
            content { string(containsString("구현 결정과 이유")) }
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
    fun `Markdown 저장은 로그인과 기록 열람 권한을 적용하고 REST와 같은 내용을 내려준다`() {
        val actor = ActorIdentity.github(42, "lim")
        val draft = records.create(command("저장할 기록 <script>"), actor)
        val path = "/records/${draft.id}/markdown"
        val anonymous = mvc.get(path).andExpect {
            status { isOk() }; header { doesNotExist(HttpHeaders.CONTENT_DISPOSITION) }
            content { string(containsString("GitHub로 로그인")) }
        }.andReturn().response.contentAsString
        assertFalse(anonymous.contains("저장할 기록"))
        val cookie = login(path)
        mvc.get("/records/${draft.id}") { cookie(cookie) }.andExpect {
            content { string(containsString("href=\"$path\">Markdown 저장</a>")) }
        }
        val downloaded = mvc.get(path) { cookie(cookie) }.andExpect {
            status { isOk() }; content { contentType("text/markdown;charset=UTF-8") }
            header { string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"intent-trace-${draft.id}.md\"") }
            header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
            header { string("X-Content-Type-Options", "nosniff") }
            header { string("Content-Security-Policy", containsString("default-src 'none'")) }
        }.andReturn().response.contentAsString
        val rest = mvc.get("/api/v1/change-records/${draft.id}/markdown") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ghu_browser-test")
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals(rest, downloaded)
        assertFalse(downloaded.contains("<script>"))
        assertEquals(draft, records.get(draft.id))
        val hidden = records.create(command("다른 작성자의 비공개 내용"), ActorIdentity.github(99, "other"))
        for (id in listOf(hidden.id, UUID.randomUUID())) {
            val unavailable = mvc.get("/records/$id/markdown") { cookie(cookie) }.andExpect {
                status { isNotFound() }; header { doesNotExist(HttpHeaders.CONTENT_DISPOSITION) }
            }.andReturn().response.contentAsString
            assertFalse(unavailable.contains(hidden.title))
        }
        mvc.get("/api/v1/change-records/${draft.id}/markdown") { cookie(cookie) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `다른 작성자의 초안은 브라우저 검색과 단건 조회에 노출하지 않는다`() {
        val draft = records.create(command("브라우저 비공개 내용"), ActorIdentity.github(99, "other"))
        val mine = records.create(command("내 비공개 내용"), ActorIdentity.github(42, "lim"))
        val cookie = login("/records?repositoryKey=acme%2Fbrowser&scope=MINE&q=비공개")
        mvc.get("/records/${draft.id}") { cookie(cookie) }.andExpect {
            status { isNotFound() }; content { string(containsString("기록이 없거나 열람 권한이 없습니다")) }
        }
        for (scope in listOf("MINE", "MY_DRAFTS")) {
            val search = mvc.get("/records") {
                cookie(cookie); param("repositoryKey", "acme/browser"); param("scope", scope); param("q", "비공개"); param("status", "DRAFT")
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString
            assertTrue(search.contains(mine.id.toString()))
            assertFalse(search.contains(draft.id.toString()))
            assertFalse(search.contains(draft.title))
            val tabs = Regex("<nav class=\"scope-tabs\"[^>]*>(.*?)</nav>").find(search)!!.groupValues[1]
            assertEquals(2, Regex("<a ").findAll(tabs).count())
            assertEquals(1, Regex("aria-current").findAll(tabs).count())
            assertTrue(tabs.contains("scope=MINE\" aria-current=\"page\">내 비공개 기록"))
            assertFalse(tabs.contains("MY_DRAFTS"))
            assertTrue(search.contains("name=\"scope\" value=\"MINE\""))
            assertTrue(search.contains("value=\"DRAFT\" selected"))
            assertFalse(search.contains("value=\"PUBLISHED\""))
            assertFalse(search.contains("내 공개 기록만 보기"))
        }
    }

    @Test
    fun `내 공개 기록 바로가기는 로그인 사용자를 필터하고 해제하면 팀 기록을 함께 보여준다`() {
        val actor = ActorIdentity.github(42, "lim")
        val repository = "acme/my-public-records"
        fun publish(owner: ActorIdentity) = records.create(command("내 공개 기록 검색").copy(repositoryKey = repository), owner).let { draft ->
            val confirmed = records.confirm(ConfirmChangeRecordCommand(draft.id, draft.version, "b".repeat(40), digest), owner)
            records.publish(PublishChangeRecordCommand(draft.id, confirmed.version, digest), owner)
        }
        val mine = publish(actor)
        val teammate = publish(ActorIdentity.github(99, "other"))
        val private = records.create(command("내 공개 기록 검색").copy(repositoryKey = repository), actor)
        val cookie = login("/records")
        val team = mvc.get("/records") {
            cookie(cookie); param("repositoryKey", repository); param("q", "공개 기록 검색")
            param("status", "PUBLISHED"); param("path", "src/App.kt")
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val mineUrl = Regex("href=\"([^\"]+)\">내 공개 기록만 보기").find(team)!!.groupValues[1].replace("&amp;", "&")
        assertTrue(URI(mineUrl).query.contains("q=공개 기록 검색"))
        assertTrue(mineUrl.contains("authorId=42"))
        assertTrue(mineUrl.contains("status=PUBLISHED"))
        assertTrue(mineUrl.contains("path=src%2FApp.kt"))
        val onlyMine = mvc.get(URI(mineUrl)) { cookie(cookie) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertTrue(onlyMine.contains(mine.id.toString()))
        assertFalse(onlyMine.contains(teammate.id.toString()))
        assertFalse(onlyMine.contains(private.id.toString()))
        val clearUrl = Regex("href=\"([^\"]+)\">작성자 필터 해제").find(onlyMine)!!.groupValues[1].replace("&amp;", "&")
        val cleared = mvc.get(URI(clearUrl)) { cookie(cookie) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertTrue(cleared.contains(mine.id.toString()))
        assertTrue(cleared.contains(teammate.id.toString()))
        assertFalse(cleared.contains(private.id.toString()))
        preview("search-my-public-records", onlyMine)
    }

    @Test
    fun `웹 목록은 폐기 상태와 파일 및 팀 작성자를 필터하고 다음 페이지와 로그인에 조건을 유지한다`() {
        val actor = ActorIdentity.github(42, "lim")
        val repo = "acme/browser-filters"
        fun draft(owner: ActorIdentity = actor) = records.create(command("필터 기록").copy(repositoryKey = repo), owner)
        val discarded = draft().let { records.discard(it, it.version, actor) }
        val other = draft(ActorIdentity.github(99, "other"))
        val returnTo = "/records?repositoryKey=acme%2Fbrowser-filters&scope=MINE&status=DISCARDED&path=src%2FApp.kt"
        val cookie = login(returnTo)
        val mine = mvc.get("/records") {
            cookie(cookie); param("repositoryKey", repo); param("scope", "MINE"); param("status", "DISCARDED"); param("path", "src/App.kt")
        }.andExpect { status { isOk() }; content { string(containsString(discarded.id.toString())) } }.andReturn().response.contentAsString
        assertFalse(mine.contains(other.id.toString()))
        preview("search-discarded", mine)
        val defaults = mvc.get("/records") { cookie(cookie); param("repositoryKey", repo); param("scope", "MINE") }.andReturn().response.contentAsString
        assertFalse(defaults.contains(discarded.id.toString()))
        repeat(21) {
            val d = draft()
            val c = records.confirm(ConfirmChangeRecordCommand(d.id, d.version, "b".repeat(40), digest), actor)
            records.publish(PublishChangeRecordCommand(d.id, c.version, digest), actor)
        }
        val team = mvc.get("/records") {
            cookie(cookie); param("repositoryKey", repo); param("status", "PUBLISHED"); param("path", "src/App.kt"); param("authorId", "42"); param("q", "필터 기록")
        }.andExpect { status { isOk() }; content { string(containsString("다음 기록")) } }.andReturn().response.contentAsString
        val next = Regex("href=\"([^\"]+)\">다음 기록").find(team)!!.groupValues[1].replace("&amp;", "&")
        assertTrue(next.contains("status=PUBLISHED")); assertTrue(next.contains("authorId=42")); assertTrue(next.contains("path=src%2FApp.kt"))
        val secondPage = mvc.get(URI(next)) { cookie(cookie) }.andExpect {
            status { isOk() }; content { string(containsString("이 페이지 1건")) }
        }.andReturn().response.contentAsString
        val clearAuthor = Regex("href=\"([^\"]+)\">작성자 필터 해제").find(secondPage)!!.groupValues[1].replace("&amp;", "&")
        assertFalse(clearAuthor.contains("authorId=")); assertFalse(clearAuthor.contains("cursor="))
        assertTrue(clearAuthor.contains("status=PUBLISHED")); assertTrue(clearAuthor.contains("path=src%2FApp.kt"))
        assertTrue(URI(clearAuthor).query.contains("q=필터 기록"))
        val recordLink = Regex("<h3><a href=\"([^\"]+)\"").find(secondPage)!!.groupValues[1].replace("&amp;", "&")
        val detail = mvc.get(URI(recordLink)) { cookie(cookie) }.andExpect {
            status { isOk() }; content { string(containsString("검색 결과로 돌아가기")) }
        }.andReturn().response.contentAsString
        val backLink = Regex("class=\"back-link\" href=\"([^\"]+)\"").find(detail)!!.groupValues[1].replace("&amp;", "&")
        assertEquals(next, backLink)
        for (label in listOf("기록 변경 이력", "GitHub 코드와 비교")) {
            val sectionLink = link(detail, label)
            assertEquals(URI(recordLink).rawQuery, sectionLink.rawQuery)
            val section = mvc.get(sectionLink) { cookie(cookie) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
            assertEquals(URI(recordLink), link(section, "기록으로 돌아가기"))
            if (label == "기록 변경 이력") {
                val older = mvc.get(URI("$sectionLink&beforeVersion=3")) { cookie(cookie) }
                    .andExpect { status { isOk() } }.andReturn().response.contentAsString
                assertEquals(URI(recordLink), link(older, "기록으로 돌아가기"))
                val direct = mvc.get(URI("${sectionLink.path}?beforeVersion=3")) { cookie(cookie) }
                    .andExpect { status { isOk() } }.andReturn().response.contentAsString
                assertEquals(URI(URI(recordLink).path), link(direct, "기록으로 돌아가기"))
            }
        }
        mvc.get(URI(backLink)) { cookie(cookie) }.andExpect {
            status { isOk() }; content { string(containsString("이 페이지 1건")) }
        }
        val renewed = login(recordLink)
        mvc.get(URI(recordLink)) { cookie(renewed) }.andExpect {
            status { isOk() }; content { string(containsString("검색 결과로 돌아가기")) }
        }
        preview("search-return", detail)
        mvc.get("/records") { cookie(cookie); param("repositoryKey", repo); param("authorId", "99"); param("path", "src/App.kt") }
            .andExpect { content { string(containsString("이 페이지 0건")) } }
        mvc.get("/records") { cookie(cookie); param("repositoryKey", repo); param("path", "src/Missing.kt") }
            .andExpect { content { string(containsString("이 페이지 0건")) } }
        mvc.get("/records") { cookie(cookie); param("repositoryKey", repo); param("status", "DISCARDED") }.andExpect { status { isBadRequest() } }
        // 검색 폼은 이전 커서를 제출하지 않고 범위 전환은 상태와 작성자 조건을 초기화한다.
        assertFalse(team.contains("name=\"cursor\""))
        preview("search-filters", team)
    }

    @Test
    fun `파일 줄과 PR 조회에서 관련 화면을 거쳐도 원래 조건과 커서로 돌아온다`() {
        val actor = ActorIdentity.github(42, "lim")
        val repository = "acme/browser-navigation"
        fun publish(title: String, originalId: UUID? = null): io.intenttrace.record.domain.ChangeRecord {
            val draft = records.create(command(title).copy(repositoryKey = repository, derivedFromRecordId = originalId), actor)
            val confirmed = records.confirm(ConfirmChangeRecordCommand(draft.id, draft.version, "b".repeat(40), digest), actor)
            return records.publish(PublishChangeRecordCommand(draft.id, confirmed.version, digest), actor)
        }
        val original = publish("원본 이동 기록")
        val successor = publish("후속 이동 기록", original.id)
        val boundary = publish("이전 페이지 마지막 기록")
        val cursor = RecordCursor(boundary.createdAt, boundary.id).encode()
        val attempt = tracking.start(successor.id, GitHubPullRequestTarget("acme", "browser-navigation", 27), PublicationOperation.PUBLISH)
        tracking.finish(attempt, PublicationAttemptStatus.RESULT_UNKNOWN, "UNKNOWN", null)
        val sources = listOf(
            URI(url("/records/history", "repositoryKey" to repository, "revision" to "b".repeat(40),
                "path" to "src/App.kt", "line" to "1", "cursor" to cursor)) to "파일·줄 조회로 돌아가기",
            URI(url("/records/pull-requests", "repositoryKey" to repository, "pullNumber" to "27", "cursor" to cursor)) to "PR 기록으로 돌아가기",
        )
        for ((source, backLabel) in sources) {
            val cookie = login(source.toString())
            fun read(uri: URI) = mvc.get(uri) { cookie(cookie) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
            val listing = read(source)
            val recordUrl = link(listing, successor.title)
            val detail = read(recordUrl)
            assertEquals(source, link(detail, backLabel))
            for (label in listOf("GitHub 코드와 비교", "기록 변경 이력")) {
                val sectionUrl = link(detail, label)
                val section = read(if (label == "기록 변경 이력") URI("$sectionUrl&beforeVersion=3") else sectionUrl)
                assertEquals(recordUrl, link(section, "기록으로 돌아가기"))
            }
            val compared = read(link(detail, "원본과 비교"))
            val changed = read(link(compared, "변경된 항목만 보기"))
            assertEquals(recordUrl, link(changed, "새 기록"))
            assertEquals(source, link(read(link(changed, "원본 기록")), backLabel))
            assertEquals(source, link(read(link(detail, "원본 공개 기록 읽기")), backLabel))
            assertTrue(read(link(detail, backLabel)).contains(successor.title))
            val renewed = login(recordUrl.toString())
            val restored = mvc.get(recordUrl) { cookie(renewed) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
            assertEquals(source, link(restored, backLabel))
        }
    }

    @Test
    fun `알 수 없는 출발 화면은 외부 주소나 다른 기록 화면으로 연결하지 않는다`() {
        val draft = records.create(command("복귀 경로 제한"), ActorIdentity.github(42, "lim"))
        val cookie = login("/records")
        for (source in listOf("https://outside.example/records", "../sessions", "github")) {
            val uri = URI(url("/records/${draft.id}", "repositoryKey" to "acme/browser", "from" to source))
            val detail = mvc.get(uri) { cookie(cookie) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
            assertEquals(URI("/records?repositoryKey=acme%2Fbrowser"), link(detail, "검색 결과로 돌아가기"))
        }
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
        val originalDraft = records.create(command("<원본 판단>"), actor)
        val confirmed = records.confirm(ConfirmChangeRecordCommand(originalDraft.id, originalDraft.version, "b".repeat(40), digest), actor)
        val original = records.publish(PublishChangeRecordCommand(originalDraft.id, confirmed.version, digest), actor)
        val successor = records.create(command("<후속 판단>").copy(derivedFromRecordId = original.id,
            decisions = listOf(Decision("공개 본문을 보존한다.", "작성자가 확인한 내용을 유지한다.", PurposeSource.CONFIRMED_AI_SUMMARY)),
            codeAnchors = listOf(CodeAnchor("src/New.kt", null, 2, 3, "d".repeat(64)))), actor)
        val target = GitHubPullRequestTarget("acme", "browser", 12)
        val attempt = tracking.start(original.id, target, PublicationOperation.PUBLISH)
        tracking.finish(attempt, PublicationAttemptStatus.RESULT_UNKNOWN, "UNKNOWN", null)
        val cookie = login("/records/pull-requests?repositoryKey=acme%2Fbrowser&pullNumber=12")
        val overview = mvc.get("/records/pull-requests") { cookie(cookie); param("repositoryKey", "acme/browser"); param("pullNumber", "12") }
            .andExpect { status { isOk() }; content { string(containsString("게시 결과 미확인")) }; content { string(containsString("PR 최신 커밋과 다름")) } }.andReturn().response.contentAsString
        preview("pull-requests", overview)
        val ciLink = Regex("href=\"([^\"]+)\">이 커밋의 CI 결과 조회").find(overview)!!.groupValues[1].replace("&amp;", "&")
        assertEquals("/records/github?repositoryKey=acme%2Fbrowser&revision=${"c".repeat(40)}", ciLink)
        val requestLink = Regex("href=\"([^\"]+)\">PR 내용 가져오기").find(overview)!!.groupValues[1].replace("&amp;", "&")
        assertEquals("/records/github?repositoryKey=acme%2Fbrowser&number=12", requestLink)
        val connectionCookie = login("/records/connection?repositoryKey=acme%2Fbrowser")
        val diagnosis = mvc.get("/records/connection") { cookie(connectionCookie); param("repositoryKey", "acme/browser"); param("pullNumber", ""); param("revision", "") }
            .andExpect { status { isOk() }; content { string(containsString("저장소 읽기")) }; content { string(containsString("확인 완료")) } }.andReturn().response.contentAsString
        preview("connection", diagnosis)
        val limitedUrl = URI(url("/records/connection", "repositoryKey" to "acme/browser", "revision" to "e".repeat(40)))
        val limited = mvc.get(limitedUrl) { cookie(connectionCookie) }.andExpect {
            status { isTooManyRequests() }; header { string(HttpHeaders.RETRY_AFTER, "12") }
            content { string(containsString("12초 후 다시 시도해 주세요.")) }
        }.andReturn().response.contentAsString
        assertEquals(limitedUrl, link(limited, "다시 조회"))
        preview("rate-limited", limited)
        val comparisonCookie = login("/records/${successor.id}/comparison")
        val searchUrl = url("/records", "repositoryKey" to "acme/browser", "scope" to "MINE", "q" to "<후속 판단> & + %")
        val successorUrl = URI("/records/${successor.id}?${URI(searchUrl).rawQuery}")
        val successorPage = mvc.get(successorUrl) { cookie(comparisonCookie) }.andReturn().response.contentAsString
        assertEquals(successorUrl.rawQuery, link(successorPage, "원본 공개 기록 읽기").rawQuery)
        val comparisonUrl = link(successorPage, "원본과 비교")
        assertEquals(successorUrl.rawQuery, comparisonUrl.rawQuery)
        val compared = mvc.get(comparisonUrl) { cookie(comparisonCookie) }
            .andExpect { status { isOk() }; content { string(containsString("새 기록에 등록된 검증 결과가 없습니다")) }; content { string(containsString("src/App.kt")) }; content { string(containsString("src/New.kt")) } }.andReturn().response.contentAsString
        preview("comparison", compared)
        assertTrue(compared.contains("내용 변경 · 출처"))
        assertTrue(compared.contains("<del>사용자 요청</del>"))
        assertTrue(compared.contains("<del>&lt;원본 판단&gt;</del>"))
        assertTrue(compared.contains("<ins>&lt;후속 판단&gt;</ins>"))
        assertTrue(compared.contains("<ins>등록된 내용 없음</ins>"))
        val changesOnly = mvc.get(link(compared, "변경된 항목만 보기")) { cookie(comparisonCookie) }
            .andExpect { status { isOk() }; content { string(containsString("변경된 항목만 표시 중")) } }.andReturn().response.contentAsString
        assertFalse(changesOnly.contains("<h2>스냅샷"))
        assertEquals(successorUrl, link(changesOnly, "새 기록"))
        assertEquals(URI("/records/${original.id}?${successorUrl.rawQuery}"), link(changesOnly, "원본 기록"))
        assertEquals("${successorUrl.rawQuery}&changesOnly=false", link(changesOnly, "같은 항목도 함께 보기").rawQuery)
        val restored = mvc.get(link(changesOnly, "새 기록")) { cookie(comparisonCookie) }.andReturn().response.contentAsString
        assertEquals(URI(searchUrl), link(restored, "검색 결과로 돌아가기"))
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
        @Bean @Primary fun gitEvidenceGateway() = object : GitEvidenceGateway {
            override fun snapshot(repository: GitHubRepository, revision: String, budget: EvidenceReadBudget?): GitEvidenceSnapshot {
                if (revision == "d".repeat(40)) throw EvidenceReadStopped(HistoryStopReason.CALL_LIMIT)
                if (revision == "e".repeat(40)) throw GitHubRateLimitException(12)
                if (revision == "f".repeat(40)) throw EvidenceUnavailableException(EvidenceUnavailableReason.TRUNCATED_TREE)
                return evidenceSnapshot.copy(revision = revision)
            }
            override fun blob(repository: GitHubRepository, sha: String, budget: EvidenceReadBudget?) = evidenceBytes
            override fun isAncestor(repository: GitHubRepository, ancestor: String, descendant: String, budget: EvidenceReadBudget?) = true
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

    private fun link(body: String, label: String): URI = URI(HtmlUtils.htmlUnescape(
        Regex("href=\"([^\"]+)\">$label</a>").find(body)!!.groupValues[1],
    ))

    private fun preview(name: String, content: String) {
        val directory = Files.createDirectories(Path.of("build/browser-preview"))
        Files.writeString(directory.resolve("$name.html"), content)
    }

    private fun command(title: String) = CreateChangeRecordCommand(
        UUID.randomUUID().toString(), "acme/browser", null, digest, title, "브라우저에서 요청과 검증을 읽는다.",
        listOf(Decision("공개 본문을 보존한다.", "작성자가 확인한 내용을 유지한다.", PurposeSource.STATED_BY_USER)),
        listOf(CodeAnchor("src/App.kt", null, 1, 2, "c".repeat(64))), emptyList(), emptyList(),
    )
    companion object {
        private val digest = "a".repeat(64)
        private val evidenceBytes = "first\nsecond\n".toByteArray()
        private val evidenceSnapshot = GitEvidenceSnapshot("b".repeat(40), mapOf("src/App.kt" to GitTreeEntry("src/App.kt", "100644", "blob", "c".repeat(40))))
    }
}
