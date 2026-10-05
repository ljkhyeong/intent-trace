package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.Messages
import java.awt.Dimension

/** 현재 파일 저장소의 권한과 HEAD 코드 읽기를 서버의 연결 진단으로 확인한다. 게시나 테스트 실행은 하지 않는다. */
class RepositoryDiagnosisAction : IntentTraceAction() {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && event.getData(CommonDataKeys.VIRTUAL_FILE)?.isDirectory == false
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val target = try {
            CurrentLineContextResolver.repository(project, file)
        } catch (error: IntentTraceUserException) {
            return Messages.showErrorDialog(project, error.message, "IntentTrace")
        }
        queueTask(project, "IntentTrace 저장소 연결 진단", "IntentTrace 연결 진단 중 예상하지 못한 오류가 발생했습니다.", {
            val server = IntentTraceServer.current()
            IntentTraceApiClient().diagnose(server, IntentTraceCredentialStore().require(server), target.repositoryKey, target.revision)
        }) { diagnosis ->
            DialogBuilder(project).title("IntentTrace 저장소 연결 진단")
                .centerPanel(readOnlyTextPane(IntentTraceTextRenderer.renderDiagnosis(diagnosis, target.revision)).apply { preferredSize = Dimension(720, 420) })
                .apply { addOkAction() }
                .show()
        }
    }
}
