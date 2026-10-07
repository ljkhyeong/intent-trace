package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class BrowseChangeRecordsAction : IntentTraceAction() {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && event.getData(CommonDataKeys.VIRTUAL_FILE)?.isDirectory == false
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        orShowError(project) {
            IntentTraceRecordBrowser.open(project, CurrentLineContextResolver.history(project, file), IntentTraceServer.current())
        }
    }
}
