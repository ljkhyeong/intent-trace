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
        val credentials = IntentTraceCredentialStore(backend, environmentUrl = { null }) { null }
        val server = IntentTraceServer.parse("https://trace.example.com")
        val token = "its_${"A".repeat(43)}"

        credentials.save(server, token)
        assertEquals(token, credentials.load(server))

        credentials.clear(server)
        assertNull(credentials.load(server))
        assertFalse(credentials.environmentSessionConfigured(server))
    }

    @Test
    fun `PasswordSafe를 삭제해도 환경 변수 session은 유지된다고 구분한다`() {
        val environmentToken = "its_${"B".repeat(43)}"
        val credentials = IntentTraceCredentialStore(
            MemoryCredentialStore(), environmentUrl = { "https://trace.example.com/" },
        ) { environmentToken }
        val server = IntentTraceServer.parse("https://trace.example.com")

        credentials.clear(server)

        assertEquals(environmentToken, credentials.load(server))
        assertNull(credentials.loadStored(server))
        assertTrue(credentials.environmentSessionConfigured(server))
    }

    @Test
    fun `서버를 바꾸면 기존 서버의 저장 세션과 환경 변수 세션을 사용하지 않는다`() {
        val environmentToken = "its_${"B".repeat(43)}"
        val savedToken = "its_${"C".repeat(43)}"
        val otherToken = "its_${"D".repeat(43)}"
        val server = IntentTraceServer.parse("https://trace.example.com")
        val other = IntentTraceServer.parse("https://other.example.com")
        val credentials = IntentTraceCredentialStore(
            MemoryCredentialStore(), environmentUrl = { "https://trace.example.com/" },
        ) { environmentToken }

        credentials.save(server, savedToken)
        assertEquals(savedToken, credentials.load(server))
        assertNull(credentials.load(other))
        assertFalse(credentials.environmentSessionConfigured(other))

        credentials.save(other, otherToken)
        credentials.clear(server)
        assertEquals(otherToken, credentials.load(other))
        assertEquals(environmentToken, credentials.load(server))
    }

    @Test
    fun `환경 변수 주소가 없으면 기본 서버에만 환경 변수 세션을 사용한다`() {
        val token = "its_${"B".repeat(43)}"
        val credentials = IntentTraceCredentialStore(MemoryCredentialStore(), environmentUrl = { null }) { token }

        assertEquals(token, credentials.load(IntentTraceServer.parse(null)))
        assertNull(credentials.load(IntentTraceServer.parse("https://trace.example.com")))
    }
}

private class MemoryCredentialStore : CredentialStore {
    private val credentials = mutableMapOf<CredentialAttributes, Credentials>()

    override fun get(attributes: CredentialAttributes): Credentials? = credentials[attributes]

    override fun set(attributes: CredentialAttributes, credentials: Credentials?) {
        if (credentials == null) this.credentials.remove(attributes) else this.credentials[attributes] = credentials
    }
}
