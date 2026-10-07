package io.intenttrace.intellij

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import java.awt.Dimension
import java.net.URI
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton

internal object LineHistory {
    fun open(project: Project, lookup: LineLookup, server: IntentTraceServer) {
        val first = IntentTraceRecordBrowser.load(project, server) { IntentTraceApiClient().history(server, it, lookup, null) } ?: return
        LineHistoryDialog(project, lookup, first, server).show()
    }
}

/** 이어 읽은 이전 커밋 조회 결과를 합친다. 살펴본 기록 수는 재개 요청끼리 합산하지 않고, 다시 확인한 기록의 이전 실패는 지운다. */
private fun ChangeIntentHistory.append(next: ChangeIntentHistory): ChangeIntentHistory {
    val retried = next.items.map { it.record.id }.toSet() + next.failures.map { it.recordId }
    val failures = failures.filter { it.recordId !in retried } + next.failures
    return next.copy(items = (items + next.items).distinct(), failures = failures, complete = next.complete && failures.isEmpty())
}

internal class LineHistoryDialog(
    private val project: Project,
    private val lookup: LineLookup,
    first: ChangeIntentHistory,
    server: IntentTraceServer,
    private val loadNext: (String) -> ChangeIntentHistory? = { cursor ->
        IntentTraceRecordBrowser.load(project, server) { token -> IntentTraceApiClient().history(server, token, lookup, cursor) }
    },
    private val openRecord: (String) -> Unit = { IntentTraceRecordBrowser.showRecord(project, it, server) },
    private val openBrowser: (URI) -> Unit = { BrowserUtil.browse(it) },
) {
    private var view = ChangeIntentHistory(emptyList(), scannedRecords = 0).append(first)
    private val webHistoryUri = server.webHistoryUri(lookup)
    private val text = readOnlyTextArea()
    private val selection = ComboBox<HistoricalIntent>().apply {
        renderer = textListCellRenderer<HistoricalIntent?> { item ->
            item?.let { "[${IntentTraceTextRenderer.matchLabel(it.match)}] ${it.record.title} · @${it.record.createdBy.login}" }
        }
    }
    private val open = JButton("선택 기록 열기").apply { addActionListener { selection.item?.let { openRecord(it.record.id) } } }
    private val next = JButton().apply { addActionListener { loadMore() } }

    internal val content = panel {
        row { scrollCell(text).align(Align.FILL) }.resizableRow()
        row("결과") {
            cell(selection).columns(COLUMNS_LARGE)
            cell(open)
        }
        row {
            cell(next)
            button("웹에서 다시 조회") { openBrowser(webHistoryUri) }
        }
    }.apply { preferredSize = Dimension(820, 560) }

    init {
        display()
    }

    fun show() = showContentDialog(project, "IntentTrace 이전 커밋 기록 · 당시 스냅샷 기준", content)

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
        selection.model = DefaultComboBoxModel(view.items.toTypedArray())
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
