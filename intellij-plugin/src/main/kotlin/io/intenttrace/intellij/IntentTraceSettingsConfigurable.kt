package io.intenttrace.intellij

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class IntentTraceSettingsConfigurable : BoundConfigurable("IntentTrace") {
    override fun createPanel(): DialogPanel {
        val settings = service<IntentTraceSettings>()
        lateinit var address: JBTextField
        return panel {
            row("서버 주소:") {
                address = textField()
                    .align(AlignX.FILL)
                    .bindText(settings::serverUrl)
                    .comment("비워 두면 INTENT_TRACE_URL 환경 변수, 없으면 http://127.0.0.1:8080을 사용합니다.")
                    .component
            }
            row {
                button("연결 확인") { checkConnection(address.text, address) }
                    .comment("입력한 주소의 서버 상태만 확인합니다. 설정 저장이나 로그인·저장소 권한 확인은 하지 않습니다.")
            }
            row {
                comment("이 설정은 모든 프로젝트에 적용됩니다. 적용 또는 확인을 누르면 다음 요청부터 새 주소를 사용합니다.")
            }
            row {
                comment("세션은 서버 주소별로 PasswordSafe에 보관합니다. 다른 서버로 바꾸면 해당 서버에서 발급받은 세션을 연결해 주세요.")
            }
        }
    }

    override fun apply() {
        try {
            super.apply()
        } catch (exception: IntentTraceUsageException) {
            throw ConfigurationException(exception.message ?: "서버 주소를 확인해 주세요.")
        }
        super.reset()
    }

    private fun checkConnection(rawUrl: String, parent: JComponent) {
        val server = try {
            service<IntentTraceSettings>().server(rawUrl)
        } catch (exception: IntentTraceUsageException) {
            Messages.showErrorDialog(parent, exception.message ?: "서버 주소를 확인해 주세요.", "IntentTrace")
            return
        }
        ProgressManager.getInstance().run(object : Task.Modal(null, "IntentTrace 서버 연결 확인", false) {
            override fun run(indicator: ProgressIndicator) {
                IntentTraceApiClient().checkConnection(server)
            }

            override fun onSuccess() {
                Messages.showInfoMessage(parent, "${server.baseUri} 서버 상태가 UP입니다. 로그인·저장소 권한은 별도로 확인해야 합니다.", "IntentTrace")
            }

            override fun onThrowable(error: Throwable) {
                val message = (error as? IntentTraceUserException)?.message ?: "서버 연결 확인을 완료하지 못했습니다."
                Messages.showErrorDialog(parent, message, "IntentTrace")
            }
        })
    }
}
