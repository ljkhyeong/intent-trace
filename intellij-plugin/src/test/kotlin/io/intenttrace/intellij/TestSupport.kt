package io.intenttrace.intellij

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.CredentialStore
import com.intellij.credentialStore.Credentials
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** 본문이 없으면 길이 없이 응답한다. 어느 경우든 교환을 닫는다. */
internal fun HttpExchange.respond(status: Int, body: String? = null) {
    val bytes = body?.toByteArray()
    sendResponseHeaders(status, bytes?.size?.toLong() ?: -1)
    if (bytes != null) responseBody.write(bytes)
    close()
}

/** loopback 임시 HTTP 서버와 그 주소로 [test]를 실행하고 끝나면 서버를 닫는다. */
internal fun withHttpServer(test: (HttpServer, IntentTraceServer) -> Unit) {
    val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    try {
        test(http, IntentTraceServer.parse("http://127.0.0.1:${http.address.port}"))
    } finally {
        http.stop(0)
    }
}

internal class MemoryCredentialStore : CredentialStore {
    private val entries = mutableMapOf<CredentialAttributes, Credentials>()

    override fun get(attributes: CredentialAttributes): Credentials? = entries[attributes]

    override fun set(attributes: CredentialAttributes, credentials: Credentials?) {
        if (credentials == null) entries.remove(attributes) else entries[attributes] = credentials
    }
}

/** 단언에 쓰는 값은 호출하는 테스트에서 copy로 지정한다. */
internal fun testRecord(id: String = "record-1") = ChangeIntentRecord(
    id = id, title = "기록", requestSummary = "요청", status = "PUBLISHED", createdBy = CreatedByResponse("developer"),
    decisions = emptyList(), codeAnchors = emptyList(), verifications = emptyList(), openQuestions = emptyList(),
    repositoryKey = "team/repository", targetRevision = "a".repeat(40),
)
