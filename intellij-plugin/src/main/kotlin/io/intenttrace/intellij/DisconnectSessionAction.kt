package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.AnActionEvent

class DisconnectSessionAction : IntentTraceAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val server = currentServerOrShowError(project) ?: return
        queueSessionTask(project, "IntentTrace 세션 삭제", "IntentTrace 세션을 삭제하지 못했습니다.") {
            disconnectSession(server, IntentTraceCredentialStore())
        }
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
