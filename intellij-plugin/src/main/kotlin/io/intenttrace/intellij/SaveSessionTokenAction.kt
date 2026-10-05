package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class SaveSessionTokenAction : IntentTraceAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val server = currentServerOrShowError(project) ?: return
        val token = Messages.showPasswordDialog(
            project,
            "${server.baseUri} 로그인 완료 화면의 its_ 세션 토큰을 입력하세요. 계정을 확인한 뒤 저장합니다.",
            "IntentTrace 세션 연결",
            Messages.getQuestionIcon(),
        )?.trim() ?: return
        if (!IntentTraceApiClient.validSessionToken(token)) {
            return Messages.showErrorDialog(project, "로그인 완료 화면에서 받은 its_ 세션 토큰을 입력하세요.", "IntentTrace")
        }
        queueSessionTask(project, "IntentTrace 세션 확인·저장", "IntentTrace 세션을 PasswordSafe에 저장하지 못했습니다.") {
            val login = connectSession(server, token, IntentTraceCredentialStore())
            "${server.baseUri}의 @$login 세션을 PasswordSafe에 저장했습니다."
        }
    }
}

internal fun connectSession(server: IntentTraceServer, sessionToken: String, credentials: IntentTraceCredentialStore): String =
    IntentTraceApiClient().checkLogin(server, sessionToken).also { credentials.save(server, sessionToken) }
