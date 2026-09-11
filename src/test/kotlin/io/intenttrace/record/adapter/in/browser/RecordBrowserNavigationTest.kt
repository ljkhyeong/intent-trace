package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.config.GitHubProperties
import io.intenttrace.config.GitHubUserAuthorizationProperties
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.ChangeIntentHistory
import io.intenttrace.record.application.ChangeRecordSummary
import io.intenttrace.record.application.HistoricalIntent
import io.intenttrace.record.application.IntentMatch
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeSide
import org.junit.jupiter.api.Test
import org.springframework.web.util.HtmlUtils
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RecordBrowserNavigationTest {
    private val actor = ActorIdentity.github(42, "author")
    private val sourceRevision = "a".repeat(40)
    private val queryRevision = "b".repeat(40)
    private val sourcePath = "src/이전 #?&\".kt"
    private val pages = RecordBrowserPage(GitHubProperties(userAuthorization = GitHubUserAuthorizationProperties(
        webBaseUrl = URI("https://github.example.test"),
    )))
    private val summary = ChangeRecordSummary(UUID.randomUUID(), "이름 변경", "요청", "acme/project",
        sourceRevision, ChangeRecordStatus.PUBLISHED, actor, Instant.EPOCH, null, 2)
    private val item = HistoricalIntent(summary, sourceRevision, CodeSide.BASE, IntentMatch.ANCESTOR_RENAMED_FILE,
        false, sourcePath, 3, 4, 3, 4)

    @Test
    fun `이름 변경과 줄 이동의 코드 링크는 각 커밋과 경로 및 줄을 가리킨다`() {
        val cases = listOf(
            item to "src/새 파일 #?&\".kt",
            item.copy(match = IntentMatch.ANCESTOR_MOVED_LINES, currentStartLine = 8, currentEndLine = 9) to sourcePath,
        )
        for ((matched, queryPath) in cases) {
            val result = ChangeIntentHistory(queryRevision, queryPath, listOf(matched), null, 1)
            val body = pages.history(actor, summary.repositoryKey, queryRevision, queryPath, matched.currentStartLine, result)
            val source = link(body, "당시 코드 열기")
            val current = link(body, "조회한 커밋의 코드 열기")
            assertEquals("/acme/project/blob/$sourceRevision/$sourcePath", source.path)
            assertEquals("L3-L4", source.fragment)
            assertEquals("/acme/project/blob/$queryRevision/$queryPath", current.path)
            assertEquals("L${matched.currentStartLine}-L${matched.currentEndLine}", current.fragment)
            for (url in listOf(source, current)) {
                assertEquals("https", url.scheme)
                assertEquals("github.example.test", url.host)
                assertNull(url.query)
            }
        }
    }

    @Test
    fun `현재 코드 위치가 미확인이면 당시 코드 링크만 제공한다`() {
        val unverified = item.copy(match = IntentMatch.RELATED_UNVERIFIED, currentStartLine = null, currentEndLine = null)
        val result = ChangeIntentHistory(queryRevision, sourcePath, listOf(unverified), null, 1)
        val body = pages.history(actor, summary.repositoryKey, queryRevision, sourcePath, 1, result)
        assertEquals("/acme/project/blob/$sourceRevision/$sourcePath", link(body, "당시 코드 열기").path)
        assertFalse(body.contains("조회한 커밋의 코드 열기"))
    }

    private fun link(body: String, label: String): URI = URI(HtmlUtils.htmlUnescape(
        Regex("href=\"([^\"]+)\">$label</a>").find(body)!!.groupValues[1],
    ))
}
