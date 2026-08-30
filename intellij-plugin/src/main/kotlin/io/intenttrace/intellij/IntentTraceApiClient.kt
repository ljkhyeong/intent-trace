package io.intenttrace.intellij

import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets

internal class IntentTraceApiClient {
    fun lookup(server: IntentTraceServer, sessionToken: String, lookup: LineLookup): List<ChangeIntentRecord> =
        IntentTraceResponseParser.parse(get(server.lookupUri(lookup), sessionToken))

    fun list(server: IntentTraceServer, sessionToken: String, query: RecordListQuery): ChangeRecordPage =
        IntentTraceResponseParser.parsePage(get(server.listUri(query), sessionToken))

    fun record(server: IntentTraceServer, sessionToken: String, id: String): ChangeIntentRecord =
        IntentTraceResponseParser.parseRecord(get(server.recordUri(id), sessionToken))

    private fun get(uri: URI, sessionToken: String): String {
        if (!SESSION_TOKEN.matches(sessionToken)) {
            throw IntentTraceUsageException("IntentTrace session token은 its_ 형식이어야 합니다.")
        }
        return try {
            HttpRequests.request(uri.toString())
                .connectTimeout(5_000)
                .readTimeout(10_000)
                .followRedirects(false)
                .throwStatusCodeException(false)
                .accept("application/json")
                .tuner { it.setRequestProperty("Authorization", "Bearer $sessionToken") }
                .connect { request ->
                    when (val status = (request.connection as HttpURLConnection).responseCode) {
                        200 -> {
                            val bytes = request.inputStream.readNBytes(MAX_RESPONSE_BYTES + 1)
                            if (bytes.size > MAX_RESPONSE_BYTES) {
                                throw IntentTraceClientException("IntentTrace 조회 응답이 허용 크기를 초과했습니다.")
                            }
                            bytes.toString(StandardCharsets.UTF_8)
                        }
                        401 -> throw IntentTraceClientException("IntentTrace session이 만료됐습니다. GitHub 승인을 다시 진행해 주세요.")
                        403 -> throw IntentTraceClientException("현재 GitHub 사용자는 이 기록을 조회할 권한이 없습니다.")
                        404 -> throw IntentTraceClientException("해당 IntentTrace 기록을 찾을 수 없습니다.")
                        in 500..599 -> throw IntentTraceClientException("IntentTrace 또는 GitHub 연동이 일시적으로 응답하지 않습니다.")
                        else -> throw IntentTraceClientException("IntentTrace 조회 요청이 거부됐습니다. HTTP $status")
                    }
                }
        } catch (_: SocketTimeoutException) {
            throw IntentTraceClientException("IntentTrace server의 응답 대기 시간을 초과했습니다.")
        } catch (_: IOException) {
            throw IntentTraceClientException("IntentTrace server에 연결하지 못했습니다.")
        }
    }

    companion object {
        const val TOKEN_ENV = "INTENT_TRACE_SESSION_TOKEN"
        private const val MAX_RESPONSE_BYTES = 1_000_000
        private val SESSION_TOKEN = Regex("^its_[A-Za-z0-9_-]{43}$")

        fun validSessionToken(value: String): Boolean = SESSION_TOKEN.matches(value)
    }
}

internal class IntentTraceClientException(message: String) : IntentTraceUserException(message)
