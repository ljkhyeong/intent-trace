package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

class DisconnectSessionAction : IntentTraceAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val server = currentServerOrShowError(project) ?: return
        object : Task.Backgroundable(project, "IntentTrace 세션 삭제", false) {
            private var message: String? = null
            private var serverFailure: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    message = disconnectSession(server, IntentTraceCredentialStore())
                } catch (error: IntentTraceRateLimitException) {
                    throw error
                } catch (error: IntentTraceClientException) {
                    // 서버가 없어졌거나 응답하지 않아도 사용자가 고르면 이 PC의 토큰만 지울 수 있게 한다.
                    serverFailure = error.message
                }
            }

            override fun onSuccess() {
                message?.let { return Messages.showInfoMessage(project, it, "IntentTrace") }
                val failure = serverFailure ?: return
                val choice = Messages.showYesNoDialog(project, localDeletionPrompt(server, failure), "IntentTrace",
                    "이 PC에서만 삭제", "취소", Messages.getWarningIcon())
                if (choice == Messages.YES) {
                    queueSessionTask(project, "IntentTrace 로컬 세션 삭제", "IntentTrace 세션을 삭제하지 못했습니다.") {
                        forgetLocalSession(server, IntentTraceCredentialStore())
                    }
                }
            }

            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(project, (error as? IntentTraceUserException)?.message ?: "IntentTrace 세션을 삭제하지 못했습니다.", "IntentTrace")
            }
        }.queue()
    }
}

internal fun disconnectSession(server: IntentTraceServer, credentials: IntentTraceCredentialStore): String {
    val sessionToken = credentials.loadStored(server)
    sessionToken?.let { IntentTraceApiClient().revokeSession(server, it) }
    credentials.clear(server)
    return withEnvironmentNote(server, credentials, if (sessionToken == null) {
        "${server.baseUri}에 삭제할 저장 세션이 없습니다."
    } else {
        "${server.baseUri}의 PasswordSafe 세션을 삭제했습니다."
    })
}

/** 서버 폐기 없이 이 PC의 저장 세션만 지운다. 사용자가 확인한 경우에만 호출한다. */
internal fun forgetLocalSession(server: IntentTraceServer, credentials: IntentTraceCredentialStore): String {
    val stored = credentials.loadStored(server) != null
    credentials.clear(server)
    return withEnvironmentNote(server, credentials, if (stored) {
        "${server.baseUri}의 PasswordSafe 세션을 이 PC에서 삭제했습니다. 서버의 연결은 만료되거나 웹의 내 연결 화면에서 종료할 때까지 남습니다."
    } else {
        "${server.baseUri}에 삭제할 저장 세션이 없습니다."
    })
}

internal fun localDeletionPrompt(server: IntentTraceServer, failure: String): String =
    "${server.baseUri}에서 세션을 폐기하지 못했습니다.\n사유: $failure\n\n" +
        "이 PC에 저장한 세션만 삭제할까요? 서버의 연결은 만료되거나 웹의 내 연결 화면에서 종료할 때까지 남습니다."

private fun withEnvironmentNote(server: IntentTraceServer, credentials: IntentTraceCredentialStore, message: String): String =
    if (credentials.environmentSessionConfigured(server)) {
        "$message INTENT_TRACE_SESSION_TOKEN 환경 변수의 세션은 계속 사용됩니다."
    } else {
        message
    }
