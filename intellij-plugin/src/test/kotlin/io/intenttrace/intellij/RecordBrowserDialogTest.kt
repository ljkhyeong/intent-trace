package io.intenttrace.intellij

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList

class RecordBrowserDialogTest : LightPlatformTestCase() {
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
}
