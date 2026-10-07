package io.intenttrace.intellij

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.net.URI
import javax.swing.Action
import javax.swing.JComponent

internal class IntentTraceResultDialog(
    private val project: Project,
    private val lookup: LineLookup,
    found: ChangeIntentLookup,
    server: IntentTraceServer,
    private val openRecord: (String) -> Unit = { IntentTraceRecordBrowser.showRecord(project, it, server) },
    private val openHistory: (RepositoryFileContext) -> Unit = { IntentTraceRecordBrowser.open(project, it, server, fileOnly = true) },
    private val openBrowser: (URI) -> Unit = { BrowserUtil.browse(it) },
    private val openLineHistory: (LineLookup) -> Unit = { LineHistory.open(project, it, server) },
) {
    private val text = IntentTraceTextRenderer.render(lookup, found)
    private val records = found.items
    private val context = RepositoryFileContext(lookup.repositoryKey, lookup.relativePath)
    private val webHistoryUri = server.webHistoryUri(lookup)

    internal val content = panel {
        row { cell(readOnlyTextPane(text)).align(Align.FILL) }.resizableRow()
        row("기록") {
            val selection = comboBox(records, textListCellRenderer<ChangeIntentRecord?> { record ->
                record?.let { "[${IntentTraceTextRenderer.status(it.status)}] ${it.title} · @${it.createdBy.login}" }
            }).columns(COLUMNS_LARGE).enabled(records.isNotEmpty()).component
            button("선택 기록 열기") { selection.item?.let { openRecord(it.id) } }.enabled(records.isNotEmpty())
        }
        row {
            button("이 파일의 과거 기록 보기") { openHistory(context) }
            button("이전 커밋에서 이 줄 찾기") { openLineHistory(lookup) }
            button("웹에서 줄 이동·이름 변경 찾기") { openBrowser(webHistoryUri) }
        }
    }.apply { preferredSize = Dimension(760, 520) }

    fun show() = showContentDialog(project, "IntentTrace 변경 의도", content)
}

/** 확인 버튼 하나만 있는 modal 창으로 [content]를 연다. 처음 포커스는 확인 버튼이다. */
internal fun showContentDialog(project: Project, dialogTitle: String, content: JComponent) {
    object : DialogWrapper(project, true) {
        init {
            title = dialogTitle
            init()
        }

        override fun createCenterPanel(): JComponent = content

        override fun createActions(): Array<Action> = arrayOf(okAction)
    }.show()
}

internal fun readOnlyTextPane(text: String): JComponent = JBScrollPane(readOnlyTextArea(text).apply { caretPosition = 0 })

internal fun readOnlyTextArea(text: String = ""): JBTextArea = JBTextArea(text).apply {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    border = JBUI.Borders.empty(12)
}
