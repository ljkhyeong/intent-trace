package io.intenttrace.intellij

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import java.net.URI
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList

class RecordBrowserDialogTest : LightPlatformTestCase() {
    private val recordId = "11111111-1111-4111-8111-111111111111"
    private val webRecordUri = IntentTraceServer.parse("https://intenttrace.example.test").webRecordUri(recordId)

    fun testOriginalAndReplacementRecordsOpenIndependentlyOnRequest() {
        val draft = ChangeIntentRecord(
            id = recordId, title = "후속 기록", requestSummary = "변경 과정 확인", status = "DRAFT",
            authorLogin = "developer", decisions = emptyList(), codeAnchors = emptyList(),
            verifications = emptyList(), openQuestions = emptyList(), repositoryKey = "team/repository",
            targetRevision = null, supersededBy = null, derivedFromRecordId = "original-id",
        )
        for (record in listOf(draft, draft.copy(status = "SUPERSEDED", supersededBy = "replacement-id"),
            draft.copy(derivedFromRecordId = null))) {
            val opened = mutableListOf<String>()
            var centerPanel: JComponent? = null
            val dialog = object : RecordHistoryDialog(project, record, webRecordUri, { opened.add(it) }) {
                override fun createCenterPanel(): JComponent = super.createCenterPanel().also { centerPanel = it }
            }
            try {
                val buttons = UIUtil.findComponentsOfType(requireNotNull(centerPanel), JButton::class.java)
                val original = buttons.single { it.text == "원본 기록 열기" }
                val replacement = buttons.single { it.text == "대체 기록 열기" }
                assertEmpty(opened)
                assertEquals(record.derivedFromRecordId != null, original.isEnabled)
                assertEquals(record.supersededBy != null, replacement.isEnabled)
                original.doClick()
                replacement.doClick()
                assertEquals(listOfNotNull(record.derivedFromRecordId, record.supersededBy), opened)
            } finally {
                dialog.close(0)
            }
        }
    }

    fun testCodeLinkUsesSelectedSideAndItsRevision() {
        val record = ChangeIntentRecord(
            id = recordId, title = "이름 변경", requestSummary = "이전 코드 확인", status = "DRAFT",
            authorLogin = "developer", decisions = emptyList(),
            codeAnchors = listOf(
                ChangeCodeAnchor("src/Before.kt", 1, 2, CodeSide.BASE),
                ChangeCodeAnchor("src/After.kt", 3, 4, CodeSide.TARGET),
            ),
            verifications = emptyList(), openQuestions = emptyList(), repositoryKey = "team/repository",
            baseRevision = "b".repeat(40), targetRevision = null, supersededBy = null,
        )
        for (candidate in listOf(record, record.copy(baseRevision = null, targetRevision = "a".repeat(40)))) {
            var centerPanel: JComponent? = null
            val dialog = object : RecordHistoryDialog(project, candidate, webRecordUri) {
                override fun createCenterPanel(): JComponent = super.createCenterPanel().also { centerPanel = it }
            }
            try {
                val panel = requireNotNull(centerPanel)
                val anchors = requireNotNull(UIUtil.findComponentOfType(panel, JComboBox::class.java))
                val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java)
                val code = buttons.single { it.text == "당시 코드 열기" }
                val commit = buttons.single { it.text == "원래 커밋 열기" }
                assertEquals(candidate.targetRevision != null, commit.isEnabled)
                assertTrue(anchors.selectedItem.toString().startsWith("[변경 전]"))
                assertEquals(candidate.baseRevision != null, code.isEnabled)
                anchors.selectedIndex = 1
                assertTrue(anchors.selectedItem.toString().startsWith("[변경 후]"))
                assertEquals(candidate.targetRevision != null, code.isEnabled)
                anchors.selectedIndex = 0
                assertEquals(candidate.baseRevision != null, code.isEnabled)
            } finally {
                dialog.close(0)
            }
        }
    }

    fun testWebRecordOpensOnlyOnRequestAndDoesNotRequireACommit() {
        val draft = ChangeIntentRecord(
            id = recordId, title = "비공개 초안", requestSummary = "웹에서 변경 이력 확인", status = "DRAFT",
            authorLogin = "developer", decisions = emptyList(), codeAnchors = emptyList(),
            verifications = emptyList(), openQuestions = emptyList(), repositoryKey = "team/repository",
            targetRevision = null, supersededBy = null,
        )
        val opened = mutableListOf<URI>()
        var centerPanel: JComponent? = null
        val dialog = object : RecordHistoryDialog(project, draft, webRecordUri, openBrowser = { opened.add(it) }) {
            override fun createCenterPanel(): JComponent = super.createCenterPanel().also { centerPanel = it }
        }
        try {
            val buttons = UIUtil.findComponentsOfType(requireNotNull(centerPanel), JButton::class.java)
            val web = buttons.single { it.text == "웹에서 기록 열기" }
            assertEmpty(opened)
            assertTrue(web.isEnabled)
            assertFalse(buttons.single { it.text == "원래 커밋 열기" }.isEnabled)
            web.doClick()
            assertEquals(listOf(URI("https://intenttrace.example.test/records/$recordId")), opened)
        } finally {
            dialog.close(0)
        }
    }

    fun testFailedQueryRestoresFiltersAndKeepsPageAndSelection() {
        val context = RepositoryFileContext("team/repository", "src/App.kt")
        val query = RecordListQuery(context.repositoryKey, path = context.relativePath, status = "PUBLISHED", page = 2)
        val record = ChangeRecordSummary(
            "record-id", "공개 기록", "PUBLISHED", "a".repeat(40), CreatedByResponse("developer"), "2026-08-30T00:00:00Z",
        )
        val initialPage = ChangeRecordPage(listOf(record), 2, 20, true)
        val requests = mutableListOf<RecordListQuery>()
        var response: ChangeRecordPage? = null
        var centerPanel: JComponent? = null
        val dialog = object : RecordBrowserDialog(project, context, query, initialPage, { requested ->
            requests.add(requested)
            response
        }) {
            // 화면 없는 SDK 창은 contentPanel을 반환하지 않아 init에서 만든 패널을 직접 받는다.
            override fun createCenterPanel(): JComponent = super.createCenterPanel().also { centerPanel = it }
        }
        try {
            val panel = requireNotNull(centerPanel)
            val filter = requireNotNull(UIUtil.findComponentOfType(panel, JComboBox::class.java))
            val fileOnly = requireNotNull(UIUtil.findComponentOfType(panel, JBCheckBox::class.java))
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
            search.doClick()

            val draftQuery = RecordListQuery(context.repositoryKey, RecordListScope.MY_DRAFTS, status = "DRAFT")
            assertEquals(draftQuery, requests.last())
            assertEquals(originalFilter, filter.selectedItem)
            assertTrue(fileOnly.isSelected)
            assertEquals(originalLabel, pageLabel.text)
            assertSame(record, list.selectedValue)
            assertTrue(previous.isEnabled)
            assertTrue(next.isEnabled)
            assertTrue(open.isEnabled)

            next.doClick()
            assertEquals(query.copy(page = 3), requests.last())
            assertEquals(originalLabel, pageLabel.text)
            assertSame(record, list.selectedValue)

            refresh.doClick()
            assertEquals(query, requests.last())
            assertEquals(originalLabel, pageLabel.text)
            assertSame(record, list.selectedValue)
            assertTrue(open.isEnabled)

            response = ChangeRecordPage(emptyList(), 0, 20, false)
            filter.selectedIndex = draftFilter
            fileOnly.isSelected = false
            search.doClick()

            assertEquals(draftQuery, requests.last())
            assertEquals(draftFilter, filter.selectedIndex)
            assertFalse(fileOnly.isSelected)
            assertEquals(0, list.model.size)
            assertTrue(pageLabel.text.contains("1페이지 · 0건"))
            assertFalse(previous.isEnabled)
            assertFalse(next.isEnabled)
            assertFalse(open.isEnabled)
        } finally {
            dialog.close(0)
        }
    }

    fun testRefreshKeepsCurrentQueryAndSelectionByIdUntilRecordLeavesPage() {
        val context = RepositoryFileContext("team/repository", "src/App.kt")
        val query = RecordListQuery(context.repositoryKey, path = context.relativePath, page = 2)
        val record = ChangeRecordSummary(
            "record-id", "공개 기록", "PUBLISHED", "a".repeat(40), CreatedByResponse("developer"), "2026-08-30T00:00:00Z",
        )
        val other = record.copy(id = "other-id", title = "다른 기록")
        val updated = record.copy(status = "SUPERSEDED")
        val initialPage = ChangeRecordPage(listOf(other, record), 2, 20, true)
        val requests = mutableListOf<RecordListQuery>()
        var response = initialPage.copy(items = listOf(updated, other), hasNext = false)
        var centerPanel: JComponent? = null
        val dialog = object : RecordBrowserDialog(project, context, query, initialPage, { requested ->
            requests.add(requested)
            response
        }) {
            override fun createCenterPanel(): JComponent = super.createCenterPanel().also { centerPanel = it }
        }
        try {
            val panel = requireNotNull(centerPanel)
            val filter = requireNotNull(UIUtil.findComponentOfType(panel, JComboBox::class.java))
            val fileOnly = requireNotNull(UIUtil.findComponentOfType(panel, JBCheckBox::class.java))
            val list = requireNotNull(UIUtil.findComponentOfType(panel, JList::class.java))
            val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java)
            val refresh = buttons.single { it.text == "새로고침" }
            val previous = buttons.single { it.text == "이전 페이지" }
            val next = buttons.single { it.text == "다음 페이지" }
            val open = buttons.single { it.text == "선택 기록 열기" }
            val pageLabel = UIUtil.findComponentsOfType(panel, JLabel::class.java).single { it.text?.contains("페이지") == true }
            val originalFilter = filter.selectedItem
            list.selectedIndex = 1
            filter.selectedIndex = (0 until filter.itemCount).first { filter.getItemAt(it).toString() == "내 비공개 기록 · 초안" }
            fileOnly.isSelected = false

            refresh.doClick()

            assertEquals(listOf(query), requests)
            assertEquals(originalFilter, filter.selectedItem)
            assertTrue(fileOnly.isSelected)
            assertSame(updated, list.selectedValue)
            assertEquals(0, list.selectedIndex)
            assertTrue(pageLabel.text.contains("3페이지 · 2건"))
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

            assertEquals(listOf(query, query, query), requests)
            assertEquals(0, list.model.size)
            assertTrue(pageLabel.text.contains("3페이지 · 0건"))
            assertTrue(previous.isEnabled)
            assertFalse(next.isEnabled)
            assertFalse(open.isEnabled)
            assertTrue(refresh.isEnabled)
        } finally {
            dialog.close(0)
        }
    }
}
