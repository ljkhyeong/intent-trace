package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

class DisconnectSessionAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val server = try {
            IntentTraceServer.fromEnvironment()
        } catch (exception: IntentTraceUserException) {
            return Messages.showErrorDialog(project, exception.message, "IntentTrace")
        }

        object : Task.Backgroundable(project, "IntentTrace 세션 삭제", false) {
            private lateinit var message: String

            override fun run(indicator: ProgressIndicator) {
                val credentials = IntentTraceCredentialStore()
                credentials.clear(server)
                message = if (credentials.environmentSessionConfigured()) {
                    "PasswordSafe 세션을 삭제했습니다. INTENT_TRACE_SESSION_TOKEN 환경 변수의 세션은 계속 사용됩니다."
                } else {
                    "${server.baseUri} PasswordSafe 세션을 삭제했습니다."
                }
            }

            override fun onSuccess() {
                Messages.showInfoMessage(project, message, "IntentTrace")
            }

            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(project, "IntentTrace 세션을 삭제하지 못했습니다.", "IntentTrace")
            }
        }.queue()
    }
}
