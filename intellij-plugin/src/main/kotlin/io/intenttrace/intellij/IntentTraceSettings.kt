package io.intenttrace.intellij

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "io.intenttrace.intellij.IntentTraceSettings", storages = [Storage("intentTrace.xml", roamingType = RoamingType.DISABLED)])
internal class IntentTraceSettings : SimplePersistentStateComponent<IntentTraceSettings.SettingsState>(SettingsState()) {
    var serverUrl: String
        get() = state.serverUrl.orEmpty()
        set(value) {
            state.serverUrl = value.trim().takeIf(String::isNotEmpty)
                ?.let { IntentTraceServer.parse(it).baseUri.toString() }.orEmpty()
        }

    fun server(
        configuredUrl: String = serverUrl,
        environmentUrl: String? = System.getenv(IntentTraceServer.URL_ENV),
    ): IntentTraceServer = IntentTraceServer.parse(configuredUrl.takeIf(String::isNotBlank) ?: environmentUrl)

    class SettingsState : BaseState() {
        var serverUrl by string("")
    }
}
