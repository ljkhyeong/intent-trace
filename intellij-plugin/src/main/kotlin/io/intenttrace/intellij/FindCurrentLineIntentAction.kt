package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

class FindCurrentLineIntentAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            event.project != null && event.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: FileDocumentManager.getInstance().getFile(editor.document)
            ?: return showError(project, "현재 편집기 파일을 확인할 수 없습니다.")
        val lookup = try {
            CurrentLineContextResolver.resolve(project, editor, file)
        } catch (exception: IntentTraceUserException) {
            return showError(project, exception.message ?: "현재 줄의 Git 문맥을 확인할 수 없습니다.")
        }

        object : Task.Backgroundable(project, "IntentTrace 변경 의도 조회", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val server = IntentTraceServer.fromEnvironment()
                    val token = IntentTraceCredentialStore().load(server)
                        ?: throw IntentTraceUsageException(
                            "IntentTrace session이 없습니다. Tools > IntentTrace 세션 연결을 먼저 실행해 주세요.",
                        )
                    val records = IntentTraceApiClient().lookup(server, token, lookup)
                    ApplicationManager.getApplication().invokeLater {
                        if (records.isEmpty()) {
                            Messages.showInfoMessage(
                                project,
                                "현재 HEAD의 ${lookup.relativePath}:${lookup.line}에 연결된 공개 변경 의도가 없습니다.",
                                "IntentTrace",
                            )
                        } else {
                            IntentTraceResultDialog(project, lookup, records).show()
                        }
                    }
                } catch (exception: IntentTraceUserException) {
                    showErrorLater(project, exception.message ?: "IntentTrace 조회를 완료하지 못했습니다.")
                } catch (_: Exception) {
                    showErrorLater(project, "IntentTrace 조회 중 예상하지 못한 오류가 발생했습니다.")
                }
            }
        }.queue()
    }
}

private fun showErrorLater(project: com.intellij.openapi.project.Project, message: String) {
    ApplicationManager.getApplication().invokeLater { showError(project, message) }
}

private fun showError(project: com.intellij.openapi.project.Project, message: String) {
    Messages.showErrorDialog(project, message, "IntentTrace")
}
