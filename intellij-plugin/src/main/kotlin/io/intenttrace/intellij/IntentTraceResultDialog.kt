package io.intenttrace.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

internal class IntentTraceResultDialog(
    project: Project,
    lookup: LineLookup,
    records: List<ChangeIntentRecord>,
) : DialogWrapper(project, true) {
    private val text = IntentTraceTextRenderer.render(lookup, records)

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
        return JBScrollPane(textArea).apply {
            preferredSize = Dimension(760, 520)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
}
