package io.intenttrace.record.application

import io.intenttrace.config.GitHubProperties
import io.intenttrace.config.GitHubHttpPolicy
import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.record.adapter.out.github.GitHubGitEvidenceClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GitHubEvidenceClientTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = GitHubGitEvidenceClient(GitHubHttpPolicy().githubApiRestClient(builder, GitHubProperties(apiBaseUrl = URI("https://api.github.test"))),
        object : CurrentGitHubUserSession {
            override fun require() = GitHubUserSession(ActorIdentity.github(1, "test"), "ghu_test")
        }, jacksonObjectMapper())
    private val repository = GitHubRepository.parse("acme/repo")
    private val revision = "a".repeat(40)
    private val tree = "b".repeat(40)
    private val blob = "c".repeat(40)

    @ParameterizedTest
    @ValueSource(strings = ["tree", "entry"])
    fun `GitHub 응답의 객체 해시 형식 오류는 원문 없이 API 오류로 처리한다`(field: String) {
        val marker = "test-private-response-marker"
        val treeSha = if (field == "tree") marker else tree
        server.expect(requestTo("https://api.github.test/repos/acme/repo/git/commits/$revision"))
            .andRespond(withSuccess("""{"sha":"$revision","tree":{"sha":"$treeSha"}}""", MediaType.APPLICATION_JSON))
        if (field == "entry") {
            server.expect(requestTo("https://api.github.test/repos/acme/repo/git/trees/$tree?recursive=1"))
                .andRespond(withSuccess("""{"sha":"$tree","truncated":false,"tree":[{"path":"sample.txt","mode":"100644","type":"blob","sha":"$marker"}]}""", MediaType.APPLICATION_JSON))
        }

        val failure = assertFailsWith<GitHubApiException> { client.snapshot(repository, revision) }

        assertEquals("GitHub 코드 응답의 객체 해시 형식이 올바르지 않습니다.", failure.message)
        assertFalse(failure.stackTraceToString().contains(marker))
        server.verify()
    }

    @ParameterizedTest
    @CsvSource("ahead,true", "identical,true", "behind,false", "diverged,false")
    fun `GitHub 비교 상태에 따라 조상 관계를 판정한다`(status: String, expected: Boolean) {
        val descendant = "d".repeat(40)
        server.expect(requestTo("https://api.github.test/repos/acme/repo/compare/$revision...$descendant?per_page=1"))
            .andRespond(withSuccess("""{"status":"$status"}""", MediaType.APPLICATION_JSON))

        assertEquals(expected, client.isAncestor(repository, revision, descendant))
        server.verify()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "test-private-response-marker"])
    fun `알 수 없는 비교 결과는 조상 관계 없음으로 처리하지 않는다`(status: String) {
        val descendant = "d".repeat(40)
        server.expect(requestTo("https://api.github.test/repos/acme/repo/compare/$revision...$descendant?per_page=1"))
            .andRespond(withSuccess("""{"status":"$status"}""", MediaType.APPLICATION_JSON))

        val failure = assertFailsWith<GitHubApiException> { client.isAncestor(repository, revision, descendant) }

        assertEquals("GitHub 커밋 비교 결과를 확인할 수 없습니다.", failure.message)
        assertFalse(failure.stackTraceToString().contains("test-private-response-marker"))
        server.verify()
    }

    @Test
    fun `커밋에 고정된 전체 트리와 blob만 읽고 잘린 트리는 거부한다`() {
        for (truncated in listOf(false, true)) {
            server.reset()
            server.expect(requestTo("https://api.github.test/repos/acme/repo/git/commits/$revision"))
                .andExpect(header("Authorization", "Bearer ghu_test"))
                .andRespond(withSuccess("""{"sha":"$revision","tree":{"sha":"$tree","url":"ignored"}}""", MediaType.APPLICATION_JSON))
            server.expect(requestTo("https://api.github.test/repos/acme/repo/git/trees/$tree?recursive=1"))
                .andRespond(withSuccess("""{"sha":"$tree","truncated":$truncated,"tree":[{"path":"sample.txt","mode":"100644","type":"blob","sha":"$blob","size":2,"url":"ignored"}]}""", MediaType.APPLICATION_JSON))
            if (truncated) {
                assertFailsWith<GitHubApiException> { client.snapshot(repository, revision) }
            } else {
                assertEquals(blob, client.snapshot(repository, revision).entries["sample.txt"]?.sha)
            }
            server.verify()
        }
        server.reset()
        server.expect(requestTo("https://api.github.test/repos/acme/repo/git/blobs/$blob"))
            .andRespond(withSuccess("""{"sha":"$blob","encoding":"base64","size":2,"content":"YQo="}""", MediaType.APPLICATION_JSON))
        assertEquals("a\n", client.blob(repository, blob).toString(Charsets.UTF_8))
        server.verify()
    }
    @Test
    fun `중복 경로는 객체 형식 검사보다 먼저 거부한다`() {
        for (mode in listOf("100644", "invalid")) {
            server.reset()
            server.expect(requestTo("https://api.github.test/repos/acme/repo/git/commits/$revision"))
                .andRespond(withSuccess("""{"sha":"$revision","tree":{"sha":"$tree"}}""", MediaType.APPLICATION_JSON))
            server.expect(requestTo("https://api.github.test/repos/acme/repo/git/trees/$tree?recursive=1"))
                .andRespond(withSuccess("""{"sha":"$tree","truncated":false,"tree":[
                    {"path":"sample.txt","mode":"$mode","type":"blob","sha":"$blob"},
                    {"path":"sample.txt","mode":"100644","type":"blob","sha":"$blob"}
                ]}""", MediaType.APPLICATION_JSON))
            val failure = assertFailsWith<GitHubApiException> { client.snapshot(repository, revision) }
            assertEquals("GitHub 트리의 경로가 중복됐습니다.", failure.message)
            server.verify()
        }
    }

    @Test
    fun `실제 HTTP 조회는 전체 기한과 호출 수를 지키고 취소 뒤에는 호출하지 않는다`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        var delayMillis = 0L
        val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            calls.incrementAndGet()
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val body = if (exchange.requestURI.path.contains("/commits/"))
                """{"sha":"$revision","tree":{"sha":"$tree"}}"""
            else """{"sha":"$tree","truncated":false,"tree":[]}"""
            try {
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            } catch (_: java.io.IOException) { exchange.close() }
        }
        http.start()
        val remote = GitHubGitEvidenceClient(GitHubHttpPolicy().githubApiRestClient(
            RestClient.builder().uriBuilderFactory(org.springframework.web.util.DefaultUriBuilderFactory("http://127.0.0.1:${http.address.port}")), GitHubProperties()),
            object : CurrentGitHubUserSession {
                override fun require() = GitHubUserSession(ActorIdentity.github(1, "test"), "ghu_local-test")
            }, jacksonObjectMapper())
        try {
            val countBudget = EvidenceReadBudget(java.time.Duration.ofSeconds(5), 1)
            assertEquals(HistoryStopReason.CALL_LIMIT, assertFailsWith<EvidenceReadStopped> { remote.snapshot(repository, revision, countBudget) }.reason)
            assertEquals(1, calls.get())
            delayMillis = 200
            val started = System.nanoTime()
            val deadline = EvidenceReadBudget(java.time.Duration.ofMillis(350), 10)
            assertEquals(HistoryStopReason.TIME_LIMIT, assertFailsWith<EvidenceReadStopped> { remote.snapshot(repository, revision, deadline) }.reason)
            kotlin.test.assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started) < java.time.Duration.ofSeconds(2))
            assertEquals(2, deadline.remoteCalls)
            val before = calls.get()
            Thread.currentThread().interrupt()
            try {
                assertEquals(HistoryStopReason.CANCELLED, assertFailsWith<EvidenceReadStopped> {
                    remote.snapshot(repository, revision, EvidenceReadBudget(java.time.Duration.ofSeconds(5), 10))
                }.reason)
                assertEquals(before, calls.get())
            } finally { Thread.interrupted() }
        } finally { http.stop(0) }
    }

}
