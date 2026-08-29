package io.intenttrace.intellij

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

internal class IntentTraceApiClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {
    fun lookup(server: IntentTraceServer, sessionToken: String, lookup: LineLookup): List<ChangeIntentRecord> {
        if (!SESSION_TOKEN.matches(sessionToken)) {
            throw IntentTraceUsageException("IntentTrace session token은 its_ 형식이어야 합니다.")
        }
        val request = HttpRequest.newBuilder(server.lookupUri(lookup))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $sessionToken")
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IntentTraceClientException("IntentTrace 조회가 중단됐습니다.", exception)
        } catch (exception: Exception) {
            throw IntentTraceClientException("IntentTrace server에 연결하지 못했습니다.", exception)
        }
        val body = response.body().use { input ->
            val bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1)
            if (bytes.size > MAX_RESPONSE_BYTES) {
                throw IntentTraceClientException("IntentTrace 조회 응답이 허용 크기를 초과했습니다.")
            }
            bytes.toString(StandardCharsets.UTF_8)
        }
        return when (response.statusCode()) {
            200 -> IntentTraceResponseParser.parse(body)
            401 -> throw IntentTraceClientException("IntentTrace session이 만료됐습니다. GitHub 승인을 다시 진행해 주세요.")
            403 -> throw IntentTraceClientException("현재 GitHub 사용자는 이 저장소의 공개 기록을 조회할 권한이 없습니다.")
            in 500..599 -> throw IntentTraceClientException("IntentTrace 또는 GitHub 연동이 일시적으로 응답하지 않습니다.")
            else -> throw IntentTraceClientException("IntentTrace 조회 요청이 거부됐습니다. HTTP ${response.statusCode()}")
        }
    }

    companion object {
        const val TOKEN_ENV = "INTENT_TRACE_SESSION_TOKEN"
        private const val MAX_RESPONSE_BYTES = 1_000_000
        private val SESSION_TOKEN = Regex("^its_[A-Za-z0-9_-]{43}$")

        fun validSessionToken(value: String): Boolean = SESSION_TOKEN.matches(value)
    }
}

internal class IntentTraceClientException(message: String, cause: Throwable? = null) :
    IntentTraceUserException(message) {
    init {
        if (cause != null) initCause(cause)
    }
}
