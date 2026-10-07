package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager

class FindCurrentLineIntentAction : IntentTraceAction() {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            event.project != null && event.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val (file, line) = orShowError(project) {
            val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
                ?: FileDocumentManager.getInstance().getFile(editor.document)
                ?: throw IntentTraceUsageException("현재 편집기 파일을 확인할 수 없습니다.")
            file to CurrentLineContextResolver.resolve(project, editor, file)
        } ?: return
        val server = orShowError(project) { IntentTraceServer.current() } ?: return
        queueTask(project, "IntentTrace 변경 의도 조회", "IntentTrace 조회 중 예상하지 못한 오류가 발생했습니다.", {
            CurrentLineContextResolver.requireUnchanged(line, CurrentLineContextResolver.refreshState(project, file))
            IntentTraceApiClient().lookup(server, IntentTraceCredentialStore().require(server), line)
        }) { IntentTraceResultDialog(project, line, it, server).show() }
    }
}
