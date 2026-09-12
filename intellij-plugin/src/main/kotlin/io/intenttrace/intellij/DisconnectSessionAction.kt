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
            IntentTraceServer.current()
        } catch (exception: IntentTraceUserException) {
            return Messages.showErrorDialog(project, exception.message, "IntentTrace")
        }

        object : Task.Backgroundable(project, "IntentTrace 세션 삭제", false) {
            private lateinit var message: String

            override fun run(indicator: ProgressIndicator) {
                message = disconnectSession(server, IntentTraceCredentialStore())
            }

            override fun onSuccess() {
                Messages.showInfoMessage(project, message, "IntentTrace")
            }

            override fun onThrowable(error: Throwable) {
                val detail = (error as? IntentTraceUserException)?.message
                    ?: "IntentTrace 세션을 삭제하지 못했습니다."
                Messages.showErrorDialog(project, detail, "IntentTrace")
            }
        }.queue()
    }
}

internal fun disconnectSession(server: IntentTraceServer, credentials: IntentTraceCredentialStore): String {
    val sessionToken = credentials.loadStored(server)
    sessionToken?.let { IntentTraceApiClient().revokeSession(server, it) }
    credentials.clear(server)
    val message = if (sessionToken == null) {
        "${server.baseUri}에 삭제할 저장 세션이 없습니다."
    } else {
        "${server.baseUri}의 PasswordSafe 세션을 삭제했습니다."
    }
    return if (credentials.environmentSessionConfigured(server)) {
        "$message INTENT_TRACE_SESSION_TOKEN 환경 변수의 세션은 계속 사용됩니다."
    } else {
        message
    }
}
