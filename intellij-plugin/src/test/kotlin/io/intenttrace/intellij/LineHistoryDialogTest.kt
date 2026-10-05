package io.intenttrace.intellij

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import java.net.URI
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent

class LineHistoryDialogTest : LightPlatformTestCase() {
    private val lookup = LineLookup("team/repository", "a".repeat(40), "src/App.kt", 12)
    private val server = IntentTraceServer.parse("https://trace.example.com")

    fun testContinuationAppendsResultsAndKeepsStateWhenLoadingFails() {
        val first = history(listOf(item("record-1")), "h1.resume", stopReason = "TIME_LIMIT",
            failures = listOf(HistoryFailure("record-2", "SIZE_LIMIT")))
        var response: ChangeIntentHistory? = history(listOf(item("record-2"), item("record-3")), null)
        val cursors = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val webPages = mutableListOf<URI>()
        var centerPanel: JComponent? = null
        val dialog = object : LineHistoryDialog(project, lookup, LineHistoryView.of(first), server,
            { cursors.add(it); response }, { opened.add(it) }, { webPages.add(it) }) {
            override fun createCenterPanel(): JComponent = super.createCenterPanel().also { centerPanel = it }
        }
        try {
            val panel = requireNotNull(centerPanel)
            val content = requireNotNull(UIUtil.findComponentOfType(panel, JBTextArea::class.java))
            val selection = requireNotNull(UIUtil.findComponentOfType(panel, JComboBox::class.java))
            val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java)
            val next = buttons.single { it.text == "중단 위치부터 계속 조회" }
            assertTrue(content.text.contains("record-2: 파일 또는 응답이 지원 크기를 초과했습니다."))
            assertEmpty(cursors)

            val resumed = response
            response = null
            next.doClick()
            assertEquals(listOf("h1.resume"), cursors)
            assertEquals(1, selection.itemCount)
            assertTrue(next.isEnabled)

            response = resumed
            next.doClick()
            assertEquals(listOf("h1.resume", "h1.resume"), cursors)
            assertEquals(3, selection.itemCount)
            assertFalse(content.text.contains("확인하지 못한 기록"))
            assertFalse(next.isEnabled)
            assertEquals("다음 기록 조회", next.text)

            selection.selectedIndex = 2
            buttons.single { it.text == "선택 기록 열기" }.doClick()
            assertEquals(listOf("record-3"), opened)
            buttons.single { it.text == "웹에서 다시 조회" }.doClick()
            assertEquals(listOf(server.webHistoryUri(lookup)), webPages)
        } finally {
            dialog.close(0)
        }
    }

    private fun history(items: List<HistoricalIntent>, nextCursor: String?, stopReason: String? = null,
        failures: List<HistoryFailure> = emptyList()) = ChangeIntentHistory(items, nextCursor,
            items.size + failures.size, failures, stopReason, stopReason == null && failures.isEmpty(), false)

    private fun item(id: String) = HistoricalIntent(
        ChangeRecordSummary(id, "기록 $id", "PUBLISHED", "b".repeat(40), CreatedByResponse("developer"), "2026-10-01T00:00:00Z"),
        "b".repeat(40), CodeSide.TARGET, "ANCESTOR_UNCHANGED_FILE", false, lookup.relativePath, 10, 14, 10, 14,
    )
}
