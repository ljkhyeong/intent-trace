package io.intenttrace.intellij

import com.intellij.configurationStore.deserialize
import com.intellij.configurationStore.serialize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IntentTraceSettingsTest {
    @Test
    fun `저장한 주소가 환경 변수보다 우선하고 비우면 환경 변수 또는 기본 주소로 돌아간다`() {
        val settings = IntentTraceSettings()
        val environment = "https://environment.example.com"

        settings.serverUrl = " https://TRACE.example.com/ "
        assertEquals("https://trace.example.com", settings.server(environmentUrl = environment).baseUri.toString())
        assertEquals("https://trace.example.com", settings.serverUrl)

        settings.serverUrl = " "
        assertEquals(environment, settings.server(environmentUrl = environment).baseUri.toString())
        assertEquals("http://127.0.0.1:8080", settings.server(environmentUrl = null).baseUri.toString())
    }

    @Test
    fun `주소 상태를 IntelliJ XML API로 복원하고 잘못된 주소는 저장하지 않는다`() {
        val settings = IntentTraceSettings().apply { serverUrl = "https://trace.example.com" }
        val restored = IntentTraceSettings().apply {
            loadState(deserialize<IntentTraceSettings.SettingsState>(requireNotNull(serialize(settings.state))))
        }

        assertEquals(settings.serverUrl, restored.serverUrl)
        assertFailsWith<IntentTraceUsageException> { restored.serverUrl = "https://user:secret@example.com" }
        assertEquals(settings.serverUrl, restored.serverUrl)
    }
}
