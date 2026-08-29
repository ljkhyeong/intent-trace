package io.intenttrace.intellij

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.CredentialStore
import com.intellij.credentialStore.Credentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntentTraceCredentialStoreTest {
    @Test
    fun `PasswordSafe에 저장한 session을 삭제한다`() {
        val backend = MemoryCredentialStore()
        val credentials = IntentTraceCredentialStore(backend) { null }
        val server = IntentTraceServer.parse("https://trace.example.com")
        val token = "its_${"A".repeat(43)}"

        credentials.save(server, token)
        assertEquals(token, credentials.load(server))

        credentials.clear(server)
        assertNull(credentials.load(server))
        assertFalse(credentials.environmentSessionConfigured())
    }

    @Test
    fun `PasswordSafe를 삭제해도 환경 변수 session은 유지된다고 구분한다`() {
        val environmentToken = "its_${"B".repeat(43)}"
        val credentials = IntentTraceCredentialStore(MemoryCredentialStore()) { environmentToken }
        val server = IntentTraceServer.parse("https://trace.example.com")

        credentials.clear(server)

        assertEquals(environmentToken, credentials.load(server))
        assertTrue(credentials.environmentSessionConfigured())
    }
}

private class MemoryCredentialStore : CredentialStore {
    private var credentials: Credentials? = null

    override fun get(attributes: CredentialAttributes): Credentials? = credentials

    override fun set(attributes: CredentialAttributes, credentials: Credentials?) {
        this.credentials = credentials
    }
}
