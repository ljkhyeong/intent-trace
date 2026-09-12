package io.intenttrace.intellij

import com.intellij.openapi.components.service
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

internal data class LineLookup(
    val repositoryKey: String,
    val revision: String,
    val relativePath: String,
    val line: Int,
) {
    init {
        require(repositoryKey.matches(Regex("^[a-z0-9_.-]+/[a-z0-9_.-]+$")))
        require(revision.matches(Regex("^(?:[0-9a-f]{40}|[0-9a-f]{64})$")))
        require(relativePath.isNotBlank() && !relativePath.startsWith('/'))
        require(line > 0)
    }
}

internal class IntentTraceServer private constructor(val baseUri: URI) {
    fun authorizationStartUri(): URI = URI.create("$baseUri/auth/github/start")

    fun healthUri(): URI = URI.create("$baseUri/actuator/health")

    fun sessionUri(): URI = URI.create("$baseUri/api/v1/session")

    fun mySessionsUri(): URI = URI.create("$baseUri/api/v1/me/sessions")

    fun recordUri(id: String): URI = URI.create("$baseUri/api/v1/change-records/${UUID.fromString(id)}")

    fun webRecordUri(id: String): URI = URI.create("$baseUri/records/${UUID.fromString(id)}")

    fun listUri(query: RecordListQuery): URI {
        val parameters = listOfNotNull(
            "repositoryKey" to query.repositoryKey,
            "scope" to query.scope.name,
            query.path?.let { "path" to it },
            query.status?.let { "status" to it },
            query.cursor?.let { "cursor" to it },
            query.keyword?.let { "q" to it },
            "limit" to "20",
        ).joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        return URI.create("$baseUri/api/v1/change-records?$parameters")
    }

    fun lookupUri(lookup: LineLookup): URI = lineUri("/api/v1/change-records/lookup", lookup)

    fun webHistoryUri(lookup: LineLookup): URI = lineUri("/records/history", lookup)

    private fun lineUri(path: String, lookup: LineLookup): URI {
        val query = listOf(
            "repositoryKey" to lookup.repositoryKey,
            "revision" to lookup.revision,
            "path" to lookup.relativePath,
            "line" to lookup.line.toString(),
        ).joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        return URI.create("$baseUri$path?$query")
    }

    companion object {
        const val URL_ENV = "INTENT_TRACE_URL"
        private const val DEFAULT_URL = "http://127.0.0.1:8080"
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")

        fun current(): IntentTraceServer = service<IntentTraceSettings>().server()

        fun parse(raw: String?): IntentTraceServer {
            val candidate = raw?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_URL
            val uri = runCatching { URI(candidate) }
                .getOrElse { throw IntentTraceUsageException("IntentTrace 서버 주소가 URL 형식이 아닙니다.") }
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if (host == null) {
                throw IntentTraceUsageException("서버 주소에서 호스트를 확인할 수 없습니다.")
            }
            val loopbackHttp = scheme == "http" && host.removeSurrounding("[", "]") in LOOPBACK_HOSTS
            if (scheme != "https" && !loopbackHttp) {
                throw IntentTraceUsageException("HTTPS 주소를 사용하세요. HTTP는 localhost·루프백 주소에서만 사용할 수 있습니다.")
            }
            if (uri.userInfo != null || uri.query != null || uri.fragment != null) {
                throw IntentTraceUsageException("서버 주소에는 사용자 정보·쿼리·프래그먼트를 넣을 수 없습니다.")
            }
            if (uri.path.orEmpty() !in setOf("", "/")) {
                throw IntentTraceUsageException("서버 주소에는 경로를 넣을 수 없습니다.")
            }
            if (uri.port !in -1..65535 || uri.port == 0) {
                throw IntentTraceUsageException("서버 포트가 올바르지 않습니다.")
            }
            val normalized = URI(scheme, null, host, uri.port, null, null, null)
            return IntentTraceServer(normalized)
        }
    }
}

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

internal open class IntentTraceUserException(message: String) : RuntimeException(message)

internal class IntentTraceUsageException(message: String) : IntentTraceUserException(message)
