package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class DisconnectSessionAction : IntentTraceAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val server = orShowError(project) { IntentTraceServer.current() } ?: return
        val failure = "IntentTrace 세션을 삭제하지 못했습니다."
        queueTask(project, "IntentTrace 세션 삭제", failure, {
            try {
                Result.success(disconnectSession(server, IntentTraceCredentialStore()))
            } catch (error: IntentTraceRateLimitException) {
                throw error
            } catch (error: IntentTraceClientException) {
                // 서버가 없어졌거나 응답하지 않아도 사용자가 고르면 이 PC의 토큰만 지울 수 있게 한다.
                Result.failure(error)
            }
        }) { result ->
            val serverFailure = result.exceptionOrNull()?.message
                ?: return@queueTask Messages.showInfoMessage(project, result.getOrThrow(), "IntentTrace")
            val choice = Messages.showYesNoDialog(project, localDeletionPrompt(server, serverFailure), "IntentTrace",
                "이 PC에서만 삭제", "취소", Messages.getWarningIcon())
            if (choice == Messages.YES) {
                queueSessionTask(project, "IntentTrace 로컬 세션 삭제", failure) {
                    disconnectSession(server, IntentTraceCredentialStore(), revoke = false)
                }
            }
        }
    }
}

/**
 * 저장 세션을 서버에서 폐기한 뒤 이 PC에서 지운다. 폐기에 실패하면 지우지 않고 예외를 전달한다.
 * [revoke]가 false면 서버 폐기 없이 이 PC의 저장 세션만 지운다. 사용자가 확인한 경우에만 false로 호출한다.
 */
internal fun disconnectSession(server: IntentTraceServer, credentials: IntentTraceCredentialStore, revoke: Boolean = true): String {
    val sessionToken = credentials.loadStored(server)
    if (revoke) sessionToken?.let { IntentTraceApiClient().revokeSession(server, it) }
    credentials.clear(server)
    val message = when {
        sessionToken == null -> "${server.baseUri}에 삭제할 저장 세션이 없습니다."
        revoke -> "${server.baseUri}의 PasswordSafe 세션을 삭제했습니다."
        else -> "${server.baseUri}의 PasswordSafe 세션을 이 PC에서 삭제했습니다. 서버의 연결은 만료되거나 웹의 내 연결 화면에서 종료할 때까지 남습니다."
    }
    return if (credentials.environmentSessionConfigured(server)) {
        "$message ${IntentTraceApiClient.TOKEN_ENV} 환경 변수의 세션은 계속 사용됩니다."
    } else {
        message
    }
}

internal fun localDeletionPrompt(server: IntentTraceServer, failure: String): String =
    "${server.baseUri}에서 세션을 폐기하지 못했습니다.\n사유: $failure\n\n" +
        "이 PC에 저장한 세션만 삭제할까요? 서버의 연결은 만료되거나 웹의 내 연결 화면에서 종료할 때까지 남습니다."
