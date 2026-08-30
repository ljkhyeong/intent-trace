package io.intenttrace.intellij

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

class OpenGitHubAuthorizationAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(event: AnActionEvent) {
        try {
            BrowserUtil.browse(IntentTraceServer.current().authorizationStartUri())
        } catch (exception: IntentTraceUserException) {
            Messages.showErrorDialog(event.project, exception.message, "IntentTrace")
        } catch (_: Exception) {
            Messages.showErrorDialog(event.project, "GitHub 승인 페이지를 열지 못했습니다.", "IntentTrace")
        }
    }
}
