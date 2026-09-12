package io.intenttrace.intellij

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.CredentialStore
import com.intellij.credentialStore.Credentials
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SessionConnectionTest {
    @Test
    fun `서버가 입력한 세션의 계정을 확인한 뒤 해당 서버의 저장 세션만 교체한다`() {
        withServer { httpServer, server ->
            val credentials = credentials()
            val other = IntentTraceServer.parse("https://other.example.com")
            credentials.save(server, previousToken)
            credentials.save(other, previousToken)
            val storedAtRequest = AtomicReference<String>()
            val authorization = AtomicReference<String>()
            val method = AtomicReference<String>()
            httpServer.createContext("/api/v1/me/sessions") { exchange ->
                storedAtRequest.set(credentials.loadStored(server))
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                method.set(exchange.requestMethod)
                val body = """{"actor":{"subject":"github:42","login":"developer"},"sessions":[]}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }

            assertEquals("developer", connectSession(server, newToken, credentials))

            assertEquals("GET", method.get())
            assertEquals("Bearer $newToken", authorization.get())
            assertEquals(previousToken, storedAtRequest.get())
            assertEquals(newToken, credentials.loadStored(server))
            assertEquals(previousToken, credentials.loadStored(other))
        }
    }

    @Test
    fun `인증 거부와 서버 오류와 잘못된 응답은 기존 저장 세션을 보존한다`() {
        withServer { httpServer, server ->
            val status = AtomicInteger()
            val requests = AtomicInteger()
            httpServer.createContext("/api/v1/me/sessions") { exchange ->
                requests.incrementAndGet()
                val body = """{"error":"test-private-response-marker"}""".toByteArray()
                exchange.sendResponseHeaders(status.get(), body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            for (existingToken in listOf(null, previousToken)) {
                val credentials = credentials()
                existingToken?.let { credentials.save(server, it) }
                for (responseStatus in listOf(401, 403, 404, 429, 503, 200)) {
                    status.set(responseStatus)
                    val requestsBefore = requests.get()

                    val error = assertFailsWith<IntentTraceClientException> {
                        connectSession(server, newToken, credentials)
                    }

                    assertEquals(existingToken, credentials.loadStored(server))
                    assertEquals(requestsBefore + 1, requests.get())
                    assertFalse(error.stackTraceToString().contains("test-private-response-marker"))
                    assertFalse(error.stackTraceToString().contains(newToken))
                }
            }
        }
    }

    @Test
    fun `연결 확인에 실패해도 환경 변수 세션을 변경하거나 저장하지 않는다`() {
        withServer { httpServer, server ->
            httpServer.createContext("/api/v1/me/sessions") { exchange ->
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
            }
            val credentials = IntentTraceCredentialStore(
                MemoryCredentialStore(), environmentUrl = { server.baseUri.toString() },
            ) { previousToken }

            assertFailsWith<IntentTraceClientException> { connectSession(server, newToken, credentials) }

            assertNull(credentials.loadStored(server))
            assertEquals(previousToken, credentials.load(server))
        }
    }

    private fun credentials() = IntentTraceCredentialStore(MemoryCredentialStore(), environmentUrl = { null }) { null }

    private fun withServer(test: (HttpServer, IntentTraceServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
        try {
            test(server, IntentTraceServer.parse("http://127.0.0.1:${server.address.port}"))
        } finally {
            server.stop(0)
        }
    }

    private class MemoryCredentialStore : CredentialStore {
        private val entries = mutableMapOf<CredentialAttributes, Credentials>()

        override fun get(attributes: CredentialAttributes): Credentials? = entries[attributes]

        override fun set(attributes: CredentialAttributes, credentials: Credentials?) {
            if (credentials == null) entries.remove(attributes) else entries[attributes] = credentials
        }
    }

    private val previousToken = "its_${"A".repeat(43)}"
    private val newToken = "its_${"B".repeat(43)}"
}
