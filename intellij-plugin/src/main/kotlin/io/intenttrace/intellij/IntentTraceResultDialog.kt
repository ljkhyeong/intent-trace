package io.intenttrace.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Action
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

internal open class IntentTraceResultDialog(
    project: Project,
    lookup: LineLookup,
    private val records: List<ChangeIntentRecord>,
    private val openRecord: (String) -> Unit = { IntentTraceRecordBrowser.showRecord(project, it) },
    private val openHistory: (RepositoryFileContext) -> Unit = {
        IntentTraceRecordBrowser.open(project, it, fileOnly = true)
    },
) : DialogWrapper(project, true) {
    private val text = IntentTraceTextRenderer.render(lookup, records)
    private val context = RepositoryFileContext(lookup.repositoryKey, lookup.relativePath)

    init {
        title = "IntentTrace 변경 의도"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val textArea = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(12)
            caretPosition = 0
        }
        return JPanel(BorderLayout()).apply {
            add(JBScrollPane(textArea), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEADING)).apply {
                val selection = JComboBox(records.map {
                    "[${IntentTraceTextRenderer.status(it.status)}] ${it.title} · @${it.authorLogin}"
                }.toTypedArray()).apply {
                    renderer = DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
                    preferredSize = Dimension(320, preferredSize.height)
                }
                add(JLabel("기록"))
                add(selection)
                add(JButton("선택 기록 열기").apply {
                    isEnabled = records.isNotEmpty()
                    addActionListener { records.getOrNull(selection.selectedIndex)?.let { openRecord(it.id) } }
                })
                add(JButton("이 파일의 과거 기록 보기").apply {
                    addActionListener { openHistory(context) }
                })
            }, BorderLayout.SOUTH)
            preferredSize = Dimension(760, 520)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
}
