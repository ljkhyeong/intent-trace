package io.intenttrace.intellij

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnActionEvent

class OpenGitHubAuthorizationAction : IntentTraceAction() {
    override fun actionPerformed(event: AnActionEvent) {
        // 브라우저 실행 실패는 BrowserUtil이 IDE 오류 안내로 표시한다.
        orShowError(event.project) { BrowserUtil.browse(IntentTraceServer.current().authorizationStartUri()) }
    }
}
