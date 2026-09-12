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

    @Test
    fun `저장 세션이 없으면 서버에 폐기를 요청하지 않고 환경 변수 연결만 안내한다`() {
        withServer { httpServer, server ->
            val requests = AtomicInteger()
            httpServer.createContext("/") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            for (environmentToken in listOf(null, previousToken)) {
                val credentials = IntentTraceCredentialStore(
                    MemoryCredentialStore(), environmentUrl = { server.baseUri.toString() },
                ) { environmentToken }

                val message = disconnectSession(server, credentials)

                val expected = "${server.baseUri}에 삭제할 저장 세션이 없습니다." +
                    if (environmentToken == null) "" else " INTENT_TRACE_SESSION_TOKEN 환경 변수의 세션은 계속 사용됩니다."
                assertEquals(expected, message)
                assertNull(credentials.loadStored(server))
                assertEquals(environmentToken, credentials.load(server))
            }
            assertEquals(0, requests.get())
        }
    }

    @Test
    fun `폐기 완료와 만료 응답 뒤 저장 세션만 삭제하고 다른 서버와 환경 변수 세션을 유지한다`() {
        withServer { httpServer, server ->
            val credentials = IntentTraceCredentialStore(
                MemoryCredentialStore(), environmentUrl = { server.baseUri.toString() },
            ) { newToken }
            val other = IntentTraceServer.parse("https://other.example.com")
            credentials.save(other, previousToken)
            val status = AtomicInteger()
            val authorization = AtomicReference<String>()
            val storedAtRequest = AtomicReference<String>()
            httpServer.createContext("/api/v1/session") { exchange ->
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                storedAtRequest.set(credentials.loadStored(server))
                exchange.sendResponseHeaders(status.get(), -1)
                exchange.close()
            }
            for (responseStatus in listOf(204, 401)) {
                status.set(responseStatus)
                credentials.save(server, previousToken)

                val message = disconnectSession(server, credentials)

                assertEquals("Bearer $previousToken", authorization.get())
                assertEquals(previousToken, storedAtRequest.get())
                assertNull(credentials.loadStored(server))
                assertEquals(newToken, credentials.load(server))
                assertEquals(previousToken, credentials.loadStored(other))
                assertEquals(
                    "${server.baseUri}의 PasswordSafe 세션을 삭제했습니다. INTENT_TRACE_SESSION_TOKEN 환경 변수의 세션은 계속 사용됩니다.",
                    message,
                )
            }
        }
    }

    @Test
    fun `세션 폐기가 호출 제한이나 서버 오류로 실패하면 저장 세션을 유지한다`() {
        withServer { httpServer, server ->
            val status = AtomicInteger()
            val requests = AtomicInteger()
            httpServer.createContext("/api/v1/session") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(status.get(), -1)
                exchange.close()
            }
            val credentials = credentials()
            credentials.save(server, previousToken)
            for (responseStatus in listOf(429, 503)) {
                status.set(responseStatus)
                val requestsBefore = requests.get()

                assertFailsWith<IntentTraceClientException> { disconnectSession(server, credentials) }

                assertEquals(previousToken, credentials.loadStored(server))
                assertEquals(requestsBefore + 1, requests.get())
            }
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
