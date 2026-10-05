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
        require(REPOSITORY_KEY.matches(repositoryKey))
        require(FULL_REVISION.matches(revision))
        require(relativePath.isNotBlank() && !relativePath.startsWith('/'))
        require(line > 0)
    }

    private companion object {
        val REPOSITORY_KEY = Regex("^[a-z0-9_.-]+/[a-z0-9_.-]+$")
        val FULL_REVISION = Regex("^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
    }
}

internal class IntentTraceServer private constructor(val baseUri: URI) {
    fun authorizationStartUri(): URI = URI.create("$baseUri/auth/github/start")

    fun healthUri(): URI = URI.create("$baseUri/actuator/health")

    fun currentSessionUri(): URI = URI.create("$baseUri/api/v1/me/sessions/current")

    fun mySessionsUri(): URI = URI.create("$baseUri/api/v1/me/sessions")

    fun recordUri(id: String): URI = URI.create("$baseUri/api/v1/change-records/${UUID.fromString(id)}")

    fun webRecordUri(id: String): URI = URI.create("$baseUri/records/${UUID.fromString(id)}")

    fun listUri(query: RecordListQuery): URI = uri(
        "/api/v1/change-records",
        "repositoryKey" to query.repositoryKey,
        "scope" to query.scope.name,
        "path" to query.path,
        "status" to query.status,
        "cursor" to query.cursor,
        "q" to query.keyword,
        "limit" to "20",
    )

    fun lookupUri(lookup: LineLookup): URI = lineUri("/api/v1/change-records/lookup", lookup)

    fun historyUri(lookup: LineLookup, cursor: String?): URI =
        lineUri("/api/v1/change-records/history", lookup, "limit" to HISTORY_LIMIT, "cursor" to cursor)

    fun diagnosticsUri(repositoryKey: String, revision: String?): URI =
        uri("/api/v1/connection-diagnostics", "repositoryKey" to repositoryKey, "revision" to revision)

    fun webHistoryUri(lookup: LineLookup): URI = lineUri("/records/history", lookup)

    private fun lineUri(path: String, lookup: LineLookup, vararg extra: Pair<String, String?>): URI = uri(
        path,
        "repositoryKey" to lookup.repositoryKey,
        "revision" to lookup.revision,
        "path" to lookup.relativePath,
        "line" to lookup.line.toString(),
        *extra,
    )

    /** 값이 없는 파라미터는 빼고 이름=값을 UTF-8로 인코딩한다. */
    private fun uri(path: String, vararg parameters: Pair<String, String?>): URI = URI.create(
        "$baseUri$path?" + parameters.mapNotNull { (name, value) -> value?.let { "$name=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" } }
            .joinToString("&"),
    )

    companion object {
        const val URL_ENV = "INTENT_TRACE_URL"
        private const val HISTORY_LIMIT = "10"
        private const val DEFAULT_URL = "http://127.0.0.1:8080"
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")

        fun current(): IntentTraceServer = service<IntentTraceSettings>().server()

        fun parse(raw: String?): IntentTraceServer {
            val candidate = raw?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_URL
            val uri = runCatching { URI(candidate) }
                .getOrElse { throw IntentTraceUsageException("IntentTrace 서버 주소가 URL 형식이 아닙니다.") }
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase() ?: throw IntentTraceUsageException("서버 주소에서 호스트를 확인할 수 없습니다.")
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
            return IntentTraceServer(URI(scheme, null, host, uri.port, null, null, null))
        }
    }
}

internal open class IntentTraceUserException(message: String) : RuntimeException(message)

internal class IntentTraceUsageException(message: String) : IntentTraceUserException(message)
