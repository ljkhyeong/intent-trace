package io.intenttrace.intellij

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import java.net.URI
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent

class IntentTraceResultDialogTest : LightPlatformTestCase() {
    fun testSelectedRecordAndFileHistoryOpenOnlyWhenRequested() {
        val lookup = LineLookup("team/repository", "a".repeat(40), "src/한글 파일.kt", 12)
        val original = ChangeIntentRecord(
            id = "record-1",
            title = "같은 제목의 기록",
            requestSummary = "현재 줄에서 상세 기록을 연다.",
            status = "SUPERSEDED",
            authorLogin = "developer",
            decisions = emptyList(),
            codeAnchors = listOf(ChangeCodeAnchor(lookup.relativePath, 10, 15)),
            verifications = emptyList(),
            openQuestions = emptyList(),
            repositoryKey = lookup.repositoryKey,
            targetRevision = lookup.revision,
            supersededBy = "record-2",
        )
        val replacement = original.copy(id = "record-2", status = "PUBLISHED", supersededBy = null)
        val openedRecords = mutableListOf<String>()
        val openedHistories = mutableListOf<RepositoryFileContext>()
        val server = IntentTraceServer.parse("https://trace.example.com")
        val historyUri = server.webHistoryUri(lookup)
        val openedWebPages = mutableListOf<URI>()
        var centerPanel: JComponent? = null
        val dialog = object : IntentTraceResultDialog(
            project, lookup, listOf(original, replacement), server,
            { openedRecords.add(it) }, { openedHistories.add(it) }, { openedWebPages.add(it) },
        ) {
            override fun createCenterPanel(): JComponent = super.createCenterPanel().also { centerPanel = it }
        }
        try {
            val panel = requireNotNull(centerPanel)
            val selection = requireNotNull(UIUtil.findComponentOfType(panel, JComboBox::class.java))
            val content = requireNotNull(UIUtil.findComponentOfType(panel, JBTextArea::class.java))
            val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java)
            val open = buttons.single { it.text == "선택 기록 열기" }
            val history = buttons.single { it.text == "이 파일의 과거 기록 보기" }
            val webHistory = buttons.single { it.text == "웹에서 줄 이동·이름 변경 찾기" }

            assertEmpty(openedRecords)
            assertEmpty(openedHistories)
            assertEmpty(openedWebPages)
            assertFalse(content.isEditable)
            assertTrue(content.text.contains(original.id))
            assertTrue(content.text.contains(replacement.id))

            open.doClick()
            selection.selectedIndex = 1
            assertEquals(listOf(original.id), openedRecords)
            assertTrue(selection.selectedItem.toString().contains("팀 공개"))
            open.doClick()
            assertEquals(listOf(original.id, replacement.id), openedRecords)

            history.doClick()
            assertEquals(listOf(RepositoryFileContext(lookup.repositoryKey, lookup.relativePath)), openedHistories)
            assertEquals(1, selection.selectedIndex)
            assertTrue(content.text.contains(original.id))
            assertEmpty(openedWebPages)
            webHistory.doClick()
            assertEquals(listOf(historyUri), openedWebPages)
        } finally {
            dialog.close(0)
        }
    }

    fun testEmptyResultKeepsBothHistoryOptionsAvailable() {
        val lookup = LineLookup("team/repository", "b".repeat(40), "src/NewName.kt", 25)
        val server = IntentTraceServer.parse("https://original.example.com")
        val historyUri = server.webHistoryUri(lookup)
        val openedRecords = mutableListOf<String>()
        val openedHistories = mutableListOf<RepositoryFileContext>()
        val openedWebPages = mutableListOf<URI>()
        var centerPanel: JComponent? = null
        val dialog = object : IntentTraceResultDialog(project, lookup, emptyList(), server,
            { openedRecords.add(it) }, { openedHistories.add(it) }, { openedWebPages.add(it) }) {
            override fun createCenterPanel(): JComponent = super.createCenterPanel().also { centerPanel = it }
        }
        try {
            val panel = requireNotNull(centerPanel)
            val selection = requireNotNull(UIUtil.findComponentOfType(panel, JComboBox::class.java))
            val content = requireNotNull(UIUtil.findComponentOfType(panel, JBTextArea::class.java))
            val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java)
            val open = buttons.single { it.text == "선택 기록 열기" }
            val history = buttons.single { it.text == "이 파일의 과거 기록 보기" }
            val webHistory = buttons.single { it.text == "웹에서 줄 이동·이름 변경 찾기" }

            assertTrue(content.text.contains("이 커밋의 현재 줄에 연결된 공개 기록이 없습니다."))
            assertFalse(selection.isEnabled)
            assertFalse(open.isEnabled)
            assertTrue(history.isEnabled)
            assertTrue(webHistory.isEnabled)
            assertEmpty(openedHistories)
            assertEmpty(openedWebPages)
            open.doClick()
            assertEmpty(openedRecords)
            webHistory.doClick()
            assertEquals(listOf(historyUri), openedWebPages)
            assertEmpty(openedHistories)
            history.doClick()
            assertEquals(listOf(RepositoryFileContext(lookup.repositoryKey, lookup.relativePath)), openedHistories)
        } finally {
            dialog.close(0)
        }
    }
}
