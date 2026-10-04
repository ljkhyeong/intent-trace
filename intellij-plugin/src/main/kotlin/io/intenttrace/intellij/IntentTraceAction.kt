package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

abstract class IntentTraceAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

internal fun currentServerOrShowError(project: Project): IntentTraceServer? = try {
    IntentTraceServer.current()
} catch (exception: IntentTraceUserException) {
    Messages.showErrorDialog(project, exception.message, "IntentTrace")
    null
}

internal fun queueSessionTask(project: Project, title: String, failure: String, work: () -> String) {
    object : Task.Backgroundable(project, title, false) {
        private lateinit var message: String

        override fun run(indicator: ProgressIndicator) {
            message = work()
        }

        override fun onSuccess() {
            Messages.showInfoMessage(project, message, "IntentTrace")
        }

        override fun onThrowable(error: Throwable) {
            Messages.showErrorDialog(project, (error as? IntentTraceUserException)?.message ?: failure, "IntentTrace")
        }
    }.queue()
}
