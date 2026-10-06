package io.intenttrace.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

abstract class IntentTraceAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/** 사용자 안내 오류는 오류창으로 표시하고 null을 반환한다. */
internal inline fun <T : Any> orShowError(project: Project?, block: () -> T): T? = try {
    block()
} catch (error: IntentTraceUserException) {
    Messages.showErrorDialog(project, error.message, "IntentTrace")
    null
}

/** [work]를 백그라운드에서 실행한다. 사용자 안내 오류는 그대로, 그 밖의 오류는 [failure]로 표시한다. */
internal fun <T : Any> queueTask(project: Project, title: String, failure: String, work: () -> T, onSuccess: (T) -> Unit) {
    object : Task.Backgroundable(project, title, false) {
        private lateinit var result: T

        override fun run(indicator: ProgressIndicator) {
            result = work()
        }

        override fun onSuccess() {
            if (!project.isDisposed) onSuccess(result)
        }

        override fun onThrowable(error: Throwable) {
            Messages.showErrorDialog(project, (error as? IntentTraceUserException)?.message ?: failure, "IntentTrace")
        }
    }.queue()
}

internal fun queueSessionTask(project: Project, title: String, failure: String, work: () -> String) =
    queueTask(project, title, failure, work) { Messages.showInfoMessage(project, it, "IntentTrace") }
