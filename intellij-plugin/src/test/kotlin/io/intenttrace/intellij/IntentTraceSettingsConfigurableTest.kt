package io.intenttrace.intellij

import com.intellij.openapi.components.service
import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import kotlin.test.assertFailsWith

class IntentTraceSettingsConfigurableTest : LightPlatformTestCase() {
    fun testApplyResetAndInvalidAddress() {
        val settings = service<IntentTraceSettings>()
        val original = settings.serverUrl
        val configurable = IntentTraceSettingsConfigurable()
        try {
            settings.serverUrl = "https://before.example.com"
            val component = configurable.createComponent()
            configurable.reset()
            val address = requireNotNull(UIUtil.findComponentOfType(component, JBTextField::class.java))

            address.text = " https://AFTER.example.com/ "
            assertTrue(configurable.isModified)
            assertEquals("https://before.example.com", IntentTraceServer.current().baseUri.toString())
            configurable.apply()
            assertEquals("https://after.example.com", IntentTraceServer.current().baseUri.toString())
            assertEquals(settings.serverUrl, address.text)
            assertFalse(configurable.isModified)

            address.text = "https://cancel.example.com"
            configurable.reset()
            assertEquals("https://after.example.com", address.text)

            address.text = "http://not-loopback.example.com"
            assertFailsWith<ConfigurationException> { configurable.apply() }
            assertEquals("https://after.example.com", settings.serverUrl)
        } finally {
            configurable.disposeUIResources()
            settings.serverUrl = original
        }
    }
}
