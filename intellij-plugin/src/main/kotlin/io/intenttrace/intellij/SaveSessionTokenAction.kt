package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

class SaveSessionTokenAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val server = try {
            IntentTraceServer.fromEnvironment()
        } catch (exception: IntentTraceUserException) {
            return Messages.showErrorDialog(project, exception.message, "IntentTrace")
        }
        val token = Messages.showPasswordDialog(
            project,
            "${server.baseUri} OAuth callback에 표시된 its_ session token을 입력하세요.",
            "IntentTrace 세션 연결",
            Messages.getQuestionIcon(),
        )?.trim() ?: return
        if (!IntentTraceApiClient.validSessionToken(token)) {
            return Messages.showErrorDialog(project, "IntentTrace session token은 its_ 형식이어야 합니다.", "IntentTrace")
        }

        object : Task.Backgroundable(project, "IntentTrace session 저장", false) {
            override fun run(indicator: ProgressIndicator) {
                IntentTraceCredentialStore().save(server, token)
            }

            override fun onSuccess() {
                Messages.showInfoMessage(
                    project,
                    "${server.baseUri} session을 PasswordSafe에 저장했습니다.",
                    "IntentTrace",
                )
            }

            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(project, "IntentTrace session을 PasswordSafe에 저장하지 못했습니다.", "IntentTrace")
            }
        }.queue()
    }
}
