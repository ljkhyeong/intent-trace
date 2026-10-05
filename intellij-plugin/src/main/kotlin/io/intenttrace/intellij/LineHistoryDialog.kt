package io.intenttrace.intellij

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.net.URI
import javax.swing.Action
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** 이어 읽은 이전 커밋 조회 결과를 합친 화면 상태다. 살펴본 기록 수는 재개 요청끼리 합산하지 않는다. */
internal data class LineHistoryView(
    val items: List<HistoricalIntent>,
    val failures: List<HistoryFailure>,
    val scannedRecords: Int,
    val nextCursor: String?,
    val stopReason: String?,
    val complete: Boolean,
    val resumeBlocked: Boolean,
) {
    fun append(next: ChangeIntentHistory): LineHistoryView {
        // 같은 기록을 다시 확인한 결과가 오면 이전 실패를 지운다.
        val retried = next.items.map { it.record.id }.toSet() + next.failures.map { it.recordId }
        val failures = failures.filter { it.recordId !in retried } + next.failures
        return LineHistoryView((items + next.items).distinct(), failures, next.scannedRecords, next.nextCursor,
            next.stopReason, next.complete && failures.isEmpty(), next.resumeBlocked)
    }

    companion object {
        fun of(first: ChangeIntentHistory): LineHistoryView =
            LineHistoryView(emptyList(), emptyList(), 0, null, null, true, false).append(first)
    }
}

internal object LineHistory {
    fun open(project: Project, lookup: LineLookup, server: IntentTraceServer) {
        val first = IntentTraceRecordBrowser.load(project, server) { source, token ->
            IntentTraceApiClient().history(source, token, lookup, null)
        } ?: return
        LineHistoryDialog(project, lookup, LineHistoryView.of(first), server).show()
    }
}

internal open class LineHistoryDialog(
    project: Project,
    private val lookup: LineLookup,
    private var view: LineHistoryView,
    server: IntentTraceServer,
    private val loadNext: (String) -> ChangeIntentHistory? = { cursor ->
        IntentTraceRecordBrowser.load(project, server) { source, token -> IntentTraceApiClient().history(source, token, lookup, cursor) }
    },
    private val openRecord: (String) -> Unit = { IntentTraceRecordBrowser.showRecord(project, it, server) },
    private val openBrowser: (URI) -> Unit = { BrowserUtil.browse(it) },
) : DialogWrapper(project, true) {
    private val webHistoryUri = server.webHistoryUri(lookup)
    private val text = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(12)
    }
    private val selection = plainComboBox(emptyList())
    private val open = JButton("선택 기록 열기")
    private val next = JButton()

    init {
        title = "IntentTrace 이전 커밋 기록 · 당시 스냅샷 기준"
        init()
        display()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(JBScrollPane(text), BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEADING)).apply {
                add(JLabel("결과"))
                add(selection)
                add(open.apply { addActionListener { view.items.getOrNull(selection.selectedIndex)?.let { openRecord(it.record.id) } } })
            }, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEADING)).apply {
                add(next.apply { addActionListener { loadMore() } })
                add(JButton("웹에서 다시 조회").apply { addActionListener { openBrowser(webHistoryUri) } })
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        preferredSize = Dimension(820, 560)
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun loadMore() {
        val cursor = view.nextCursor ?: return
        // 실패하면 기존 결과와 커서를 그대로 둔다.
        val loaded = loadNext(cursor) ?: return
        view = view.append(loaded)
        display()
    }

    private fun display() {
        text.text = IntentTraceTextRenderer.renderLineHistory(lookup, view)
        text.caretPosition = 0
        selection.model = DefaultComboBoxModel(view.items.map {
            "[${IntentTraceTextRenderer.matchLabel(it.match)}] ${it.record.title} · @${it.record.createdBy.login}"
        }.toTypedArray())
        selection.isEnabled = view.items.isNotEmpty()
        open.isEnabled = view.items.isNotEmpty()
        next.text = when {
            view.resumeBlocked -> "원인 확인 후 다시 조회"
            view.stopReason != null -> "중단 위치부터 계속 조회"
            else -> "다음 기록 조회"
        }
        next.isEnabled = view.nextCursor != null
    }
}
