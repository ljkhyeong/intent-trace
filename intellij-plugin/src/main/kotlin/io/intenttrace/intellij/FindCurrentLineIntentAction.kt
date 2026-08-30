package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
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
            private lateinit var records: List<ChangeIntentRecord>

            override fun run(indicator: ProgressIndicator) {
                val server = IntentTraceServer.current()
                val token = IntentTraceCredentialStore().load(server)
                    ?: throw IntentTraceUsageException(
                        "IntentTrace session이 없습니다. Tools > IntentTrace 세션 연결을 먼저 실행해 주세요.",
                    )
                records = IntentTraceApiClient().lookup(server, token, lookup)
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                if (records.isEmpty()) {
                    val choice = Messages.showYesNoDialog(
                        project,
                        "현재 HEAD의 ${lookup.relativePath}:${lookup.line}에 연결된 공개 변경 의도가 없습니다.\n이 파일의 다른 커밋에 기록된 의도를 볼까요?",
                        "IntentTrace",
                        "이 파일의 과거 기록 보기",
                        "닫기",
                        Messages.getQuestionIcon(),
                    )
                    if (choice == Messages.YES) {
                        IntentTraceRecordBrowser.open(project, RepositoryFileContext(lookup.repositoryKey, lookup.relativePath), fileOnly = true)
                    }
                } else {
                    IntentTraceResultDialog(project, lookup, records).show()
                }
            }

            override fun onThrowable(error: Throwable) {
                val message = if (error is IntentTraceUserException) {
                    error.message ?: "IntentTrace 조회를 완료하지 못했습니다."
                } else {
                    "IntentTrace 조회 중 예상하지 못한 오류가 발생했습니다."
                }
                showError(project, message)
            }
        }.queue()
    }
}

private fun showError(project: com.intellij.openapi.project.Project, message: String) {
    Messages.showErrorDialog(project, message, "IntentTrace")
}
