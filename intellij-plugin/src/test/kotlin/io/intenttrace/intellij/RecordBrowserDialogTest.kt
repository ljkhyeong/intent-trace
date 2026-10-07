package io.intenttrace.intellij

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JTextField

class RecordBrowserDialogTest : LightPlatformTestCase() {
    private val recordId = "11111111-1111-4111-8111-111111111111"
    private val server = IntentTraceServer.parse("https://intenttrace.example.test")

    fun testPaginationKeepsOriginalServerAndReadsItsLatestSession() {
        val requests = CopyOnWriteArrayList<Pair<URI, String>>()
        val original = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val servers = listOf(original, other)
        val endpoints = servers.map { IntentTraceServer.parse("http://127.0.0.1:${it.address.port}") }
        val settings = service<IntentTraceSettings>()
        val previousUrl = settings.serverUrl
        val credentials = IntentTraceCredentialStore()
        val previousTokens = endpoints.map(credentials::loadStored)
        try {
            servers.zip(endpoints).forEach { (http, endpoint) ->
                http.createContext("/api/v1/change-records") { exchange ->
                    requests.add(endpoint.baseUri.resolve(exchange.requestURI) to exchange.requestHeaders.getFirst("Authorization"))
                    exchange.respond(200, """{"items":[],"nextCursor":null}""")
                }
                http.start()
            }
            val token = "its_${"A".repeat(43)}"
            val renewedToken = "its_${"B".repeat(43)}"
            credentials.save(endpoints[0], token)
            credentials.save(endpoints[1], "its_${"C".repeat(43)}")
            settings.serverUrl = endpoints[0].baseUri.toString()
            val context = RepositoryFileContext("team/repository", "src/App.kt")
            val query = RecordListQuery(context.repositoryKey)
            val panel = RecordBrowserDialog(project, context, query, ChangeRecordPage(emptyList(), "original-cursor"), endpoints[0]).content
            settings.serverUrl = endpoints[1].baseUri.toString()
            val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java)
            assertEmpty(requests)

            buttons.single { it.text == "다음 페이지" }.doClick()
            credentials.save(endpoints[0], renewedToken)
            buttons.single { it.text == "새로고침" }.doClick()

            val expectedUri = endpoints[0].listUri(query.copy(cursor = "original-cursor"))
            assertEquals(listOf(expectedUri to "Bearer $token", expectedUri to "Bearer $renewedToken"), requests.toList())
        } finally {
            settings.serverUrl = previousUrl
            endpoints.zip(previousTokens).forEach { (server, token) ->
                if (token == null) credentials.clear(server) else credentials.save(server, token)
            }
            servers.forEach { it.stop(0) }
        }
    }

    fun testOriginalAndReplacementRecordsOpenIndependentlyOnRequest() {
        val draft = testRecord(recordId).copy(status = "DRAFT", targetRevision = null, derivedFromRecordId = "original-id")
        for (record in listOf(draft, draft.copy(status = "SUPERSEDED", supersededBy = "replacement-id"),
            draft.copy(derivedFromRecordId = null))) {
            val opened = mutableListOf<String>()
            val panel = RecordHistoryDialog(project, record, server, { opened.add(it) }).content
            val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java)
            val original = buttons.single { it.text == "원본 기록 열기" }
            val replacement = buttons.single { it.text == "대체 기록 열기" }
            assertEmpty(opened)
            assertEquals(record.derivedFromRecordId != null, original.isEnabled)
            assertEquals(record.supersededBy != null, replacement.isEnabled)
            original.doClick()
            replacement.doClick()
            assertEquals(listOfNotNull(record.derivedFromRecordId, record.supersededBy), opened)
        }
    }

    fun testCodeLinkUsesSelectedSideAndItsRevision() {
        val record = testRecord(recordId).copy(
            status = "DRAFT", baseRevision = "b".repeat(40), targetRevision = null,
            codeAnchors = listOf(ChangeCodeAnchor("src/Before.kt", 1, 2, CodeSide.BASE), ChangeCodeAnchor("src/After.kt", 3, 4, CodeSide.TARGET)),
        )
        for (candidate in listOf(record, record.copy(baseRevision = null, targetRevision = "a".repeat(40)))) {
            val panel = RecordHistoryDialog(project, candidate, server).content
            val anchors = requireNotNull(UIUtil.findComponentOfType(panel, JComboBox::class.java))
            val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java)
            val code = buttons.single { it.text == "당시 코드 열기" }
            val commit = buttons.single { it.text == "원래 커밋 열기" }
            assertEquals(candidate.targetRevision != null, commit.isEnabled)
            assertEquals(CodeSide.BASE, (anchors.selectedItem as ChangeCodeAnchor).side)
            assertEquals(candidate.baseRevision != null, code.isEnabled)
            anchors.selectedIndex = 1
            assertEquals(CodeSide.TARGET, (anchors.selectedItem as ChangeCodeAnchor).side)
            assertEquals(candidate.targetRevision != null, code.isEnabled)
            anchors.selectedIndex = 0
            assertEquals(candidate.baseRevision != null, code.isEnabled)
        }
    }

    fun testWebRecordOpensOnlyOnRequestAndDoesNotRequireACommit() {
        val draft = testRecord(recordId).copy(status = "DRAFT", targetRevision = null)
        val opened = mutableListOf<URI>()
        val panel = RecordHistoryDialog(project, draft, server, openBrowser = { opened.add(it) }).content
        val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java)
        val web = buttons.single { it.text == "웹에서 기록 열기" }
        assertEmpty(opened)
        assertTrue(web.isEnabled)
        assertFalse(buttons.single { it.text == "원래 커밋 열기" }.isEnabled)
        web.doClick()
        assertEquals(listOf(URI("https://intenttrace.example.test/records/$recordId")), opened)
    }

    fun testFailedQueryRestoresFiltersAndKeepsPageAndSelection() {
        val context = RepositoryFileContext("team/repository", "src/App.kt")
        val query = RecordListQuery(context.repositoryKey, path = context.relativePath, status = "PUBLISHED", keyword = "공개")
        val record = ChangeRecordSummary(
            "record-id", "공개 기록", "PUBLISHED", "a".repeat(40), CreatedByResponse("developer"), "2026-08-30T00:00:00Z",
        )
        val initialPage = ChangeRecordPage(listOf(record), "page-2")
        val requests = mutableListOf<RecordListQuery>()
        var response: ChangeRecordPage? = null
        val panel = RecordBrowserDialog(project, context, query, initialPage, server, { requested ->
            requests.add(requested)
            response
        }).content
        val filter = requireNotNull(UIUtil.findComponentOfType(panel, JComboBox::class.java))
        val fileOnly = requireNotNull(UIUtil.findComponentOfType(panel, JBCheckBox::class.java))
        val keyword = requireNotNull(UIUtil.findComponentOfType(panel, JTextField::class.java))
        val list = requireNotNull(UIUtil.findComponentOfType(panel, JList::class.java))
        val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java)
        val search = buttons.single { it.text == "조회" }
        val previous = buttons.single { it.text == "이전 페이지" }
        val next = buttons.single { it.text == "다음 페이지" }
        val refresh = buttons.single { it.text == "새로고침" }
        val open = buttons.single { it.text == "선택 기록 열기" }
        val pageLabel = UIUtil.findComponentsOfType(panel, JLabel::class.java).single { it.text?.contains("페이지") == true }
        val originalLabel = pageLabel.text
        val originalFilter = filter.selectedItem
        val draftFilter = (0 until filter.itemCount).first { filter.getItemAt(it).toString() == "내 비공개 기록 · 초안" }
        list.selectedIndex = 0

        filter.selectedIndex = draftFilter
        fileOnly.isSelected = false
        keyword.text = "  세션  "
        search.doClick()

        val draftQuery = RecordListQuery(context.repositoryKey, RecordListScope.MINE, status = "DRAFT", keyword = "세션")
        assertEquals(draftQuery, requests.last())
        assertEquals(originalFilter, filter.selectedItem)
        assertTrue(fileOnly.isSelected)
        assertEquals("공개", keyword.text)
        assertEquals(originalLabel, pageLabel.text)
        assertSame(record, list.selectedValue)
        assertFalse(previous.isEnabled)
        assertTrue(next.isEnabled)
        assertTrue(open.isEnabled)

        next.doClick()
        assertEquals(query.copy(cursor = "page-2"), requests.last())
        assertEquals(originalLabel, pageLabel.text)
        assertSame(record, list.selectedValue)

        refresh.doClick()
        assertEquals(query, requests.last())
        assertEquals(originalLabel, pageLabel.text)
        assertSame(record, list.selectedValue)
        assertTrue(open.isEnabled)

        response = ChangeRecordPage(emptyList(), null)
        filter.selectedIndex = draftFilter
        fileOnly.isSelected = false
        keyword.text = "  세션  "
        keyword.postActionEvent()

        assertEquals(draftQuery, requests.last())
        assertEquals(draftFilter, filter.selectedIndex)
        assertFalse(fileOnly.isSelected)
        assertEquals("세션", keyword.text)
        assertEquals(0, list.model.size)
        assertTrue(pageLabel.text.contains("1페이지 · 0건"))
        assertFalse(previous.isEnabled)
        assertFalse(next.isEnabled)
        assertFalse(open.isEnabled)
    }

    fun testRefreshKeepsCurrentQueryAndSelectionByIdUntilRecordLeavesPage() {
        val context = RepositoryFileContext("team/repository", "src/App.kt")
        val query = RecordListQuery(context.repositoryKey, path = context.relativePath, keyword = "변경")
        val record = ChangeRecordSummary(
            "record-id", "공개 기록", "PUBLISHED", "a".repeat(40), CreatedByResponse("developer"), "2026-08-30T00:00:00Z",
        )
        val other = record.copy(id = "other-id", title = "다른 기록")
        val updated = record.copy(status = "SUPERSEDED")
        val initialPage = ChangeRecordPage(listOf(other, record), "page-2")
        val requests = mutableListOf<RecordListQuery>()
        var response = initialPage.copy(items = listOf(updated, other), nextCursor = null)
        val panel = RecordBrowserDialog(project, context, query, initialPage, server, { requested ->
            requests.add(requested)
            response
        }).content
        val filter = requireNotNull(UIUtil.findComponentOfType(panel, JComboBox::class.java))
        val fileOnly = requireNotNull(UIUtil.findComponentOfType(panel, JBCheckBox::class.java))
        val keyword = requireNotNull(UIUtil.findComponentOfType(panel, JTextField::class.java))
        val list = requireNotNull(UIUtil.findComponentOfType(panel, JList::class.java))
        val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java)
        val refresh = buttons.single { it.text == "새로고침" }
        val previous = buttons.single { it.text == "이전 페이지" }
        val next = buttons.single { it.text == "다음 페이지" }
        val open = buttons.single { it.text == "선택 기록 열기" }
        val pageLabel = UIUtil.findComponentsOfType(panel, JLabel::class.java).single { it.text?.contains("페이지") == true }
        val originalFilter = filter.selectedItem
        next.doClick()
        val currentQuery = query.copy(cursor = "page-2")
        requests.clear()
        list.selectedIndex = 0
        filter.selectedIndex = (0 until filter.itemCount).first { filter.getItemAt(it).toString() == "내 비공개 기록 · 초안" }
        fileOnly.isSelected = false
        keyword.text = "아직 적용하지 않은 검색어"

        response = response.copy(items = listOf(other, updated))

        refresh.doClick()

        assertEquals(listOf(currentQuery), requests)
        assertEquals(originalFilter, filter.selectedItem)
        assertTrue(fileOnly.isSelected)
        assertEquals("변경", keyword.text)
        assertSame(updated, list.selectedValue)
        assertEquals(1, list.selectedIndex)
        assertTrue(pageLabel.text.contains("2페이지 · 2건"))
        assertTrue(previous.isEnabled)
        assertFalse(next.isEnabled)
        assertTrue(open.isEnabled)

        response = response.copy(items = listOf(other))
        refresh.doClick()

        assertNull(list.selectedValue)
        assertEquals(1, list.model.size)
        assertFalse(open.isEnabled)

        response = response.copy(items = emptyList())
        refresh.doClick()

        assertEquals(listOf(currentQuery, currentQuery, currentQuery), requests)
        assertEquals(0, list.model.size)
        assertTrue(pageLabel.text.contains("2페이지 · 0건"))
        assertTrue(previous.isEnabled)
        assertFalse(next.isEnabled)
        assertFalse(open.isEnabled)
        assertTrue(refresh.isEnabled)
    }

    fun testCursorNavigationRetainsHistoryOnFailureAndResetsForNewSearch() {
        val context = RepositoryFileContext("team/repository", "src/App.kt")
        val query = RecordListQuery(context.repositoryKey, path = context.relativePath, keyword = "로그인")
        val initialPage = ChangeRecordPage(emptyList(), "page-2")
        val requests = mutableListOf<RecordListQuery>()
        var response: ChangeRecordPage? = initialPage.copy(nextCursor = "page-3")
        val panel = RecordBrowserDialog(project, context, query, initialPage, server, { requested ->
            requests.add(requested)
            response
        }).content
        val filter = requireNotNull(UIUtil.findComponentOfType(panel, JComboBox::class.java))
        val keyword = requireNotNull(UIUtil.findComponentOfType(panel, JTextField::class.java))
        val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java)
        val previous = buttons.single { it.text == "이전 페이지" }
        val next = buttons.single { it.text == "다음 페이지" }
        val search = buttons.single { it.text == "조회" }
        val pageLabel = UIUtil.findComponentsOfType(panel, JLabel::class.java).single { it.text?.contains("페이지") == true }

        next.doClick()
        assertEquals(query.copy(cursor = "page-2"), requests.last())
        assertTrue(pageLabel.text.contains("2페이지"))

        response = null
        next.doClick()
        assertEquals(query.copy(cursor = "page-3"), requests.last())
        previous.doClick()
        assertEquals(query, requests.last())
        assertTrue(pageLabel.text.contains("2페이지"))
        assertTrue(previous.isEnabled)

        response = initialPage.copy(nextCursor = "new-page-2")
        previous.doClick()
        assertEquals(query, requests.last())
        assertTrue(pageLabel.text.contains("1페이지"))
        assertFalse(previous.isEnabled)
        next.doClick()
        assertEquals(query.copy(cursor = "new-page-2"), requests.last())
        assertTrue(pageLabel.text.contains("2페이지"))

        filter.selectedIndex = (0 until filter.itemCount).first { filter.getItemAt(it).toString() == "내 비공개 기록 · 폐기" }
        keyword.text = "  폐기 사유  "
        response = ChangeRecordPage(emptyList(), null)
        search.doClick()
        assertEquals(RecordListQuery(context.repositoryKey, RecordListScope.MINE, context.relativePath,
            "DISCARDED", keyword = "폐기 사유"), requests.last())
        assertTrue(pageLabel.text.contains("1페이지"))
        assertFalse(previous.isEnabled)
        assertFalse(next.isEnabled)

        keyword.text = "   "
        keyword.postActionEvent()
        assertNull(requests.last().keyword)
        assertEquals("", keyword.text)
    }

    fun testLongKeywordIsRejectedBeforeRequestAndKeepsInput() {
        val context = RepositoryFileContext("team/repository", "src/App.kt")
        val requests = mutableListOf<RecordListQuery>()
        val messages = mutableListOf<String>()
        val previousDialog = TestDialogManager.setTestDialog(TestDialog { message -> messages.add(message); Messages.OK })
        val panel = RecordBrowserDialog(project, context, RecordListQuery(context.repositoryKey),
            ChangeRecordPage(emptyList(), null), server, { requests.add(it); ChangeRecordPage(emptyList(), null) }).content
        try {
            val keyword = requireNotNull(UIUtil.findComponentOfType(panel, JTextField::class.java))
            keyword.text = "가".repeat(201)
            keyword.postActionEvent()
            assertEmpty(requests)
            assertEquals(listOf("검색어는 200자 이하로 입력해 주세요."), messages)
            assertEquals(201, keyword.text.length)

            keyword.text = " ${"가".repeat(200)} "
            keyword.postActionEvent()
            assertEquals("가".repeat(200), requests.single().keyword)
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
    }
}
