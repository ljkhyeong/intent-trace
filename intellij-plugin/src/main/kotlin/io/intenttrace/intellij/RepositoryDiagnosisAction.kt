package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

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
        object : Task.Backgroundable(project, "IntentTrace 저장소 연결 진단", false) {
            private lateinit var diagnosis: ConnectionDiagnosis

            override fun run(indicator: ProgressIndicator) {
                val server = IntentTraceServer.current()
                val token = IntentTraceCredentialStore().load(server)
                    ?: throw IntentTraceUsageException("IntentTrace 세션이 없습니다. Tools > IntentTrace 세션 연결을 먼저 실행해 주세요.")
                diagnosis = IntentTraceApiClient().diagnose(server, token, target.repositoryKey, target.revision)
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                IntentTraceTextDialog(project, "IntentTrace 저장소 연결 진단",
                    IntentTraceTextRenderer.renderDiagnosis(diagnosis, target.revision)).show()
            }

            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(project, (error as? IntentTraceUserException)?.message
                    ?: "IntentTrace 연결 진단 중 예상하지 못한 오류가 발생했습니다.", "IntentTrace")
            }
        }.queue()
    }
}

internal class IntentTraceTextDialog(project: Project, title: String, private val text: String) : DialogWrapper(project, true) {
    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent = readOnlyTextPane(text).apply { preferredSize = Dimension(720, 420) }

    override fun createActions(): Array<Action> = arrayOf(okAction)
}
