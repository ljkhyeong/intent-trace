package io.intenttrace.intellij

import com.intellij.util.io.HttpRequests
import com.intellij.util.io.RequestBuilder
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets

internal class IntentTraceApiClient {
    fun checkConnection(server: IntentTraceServer) {
        if (decodeResponse<HealthResponse>(get(server.healthUri(), null)).status != "UP") {
            throw IntentTraceClientException("IntentTrace 서버가 정상 상태(UP)가 아닙니다.")
        }
    }

    fun checkLogin(server: IntentTraceServer, sessionToken: String): String =
        decodeResponse<LoginResponse>(get(server.mySessionsUri(), sessionToken, sessionCheck = true)).actor.login

    fun lookup(server: IntentTraceServer, sessionToken: String, lookup: LineLookup): ChangeIntentLookup =
        decodeResponse(get(server.lookupUri(lookup), sessionToken))

    fun list(server: IntentTraceServer, sessionToken: String, query: RecordListQuery): ChangeRecordPage =
        decodeResponse(get(server.listUri(query), sessionToken))

    fun record(server: IntentTraceServer, sessionToken: String, id: String): ChangeIntentRecord =
        decodeResponse(get(server.recordUri(id), sessionToken))

    // 아래 두 조회는 서버가 GitHub 코드를 읽으므로 서버의 기본 30초 조회 기한보다 길게 기다린다.
    fun history(server: IntentTraceServer, sessionToken: String, lookup: LineLookup, cursor: String?): ChangeIntentHistory =
        decodeResponse(get(server.historyUri(lookup, cursor), sessionToken, readTimeout = REMOTE_READ_TIMEOUT))

    fun diagnose(server: IntentTraceServer, sessionToken: String, repositoryKey: String, revision: String?): ConnectionDiagnosis =
        decodeResponse(get(server.diagnosticsUri(repositoryKey, revision), sessionToken, readTimeout = REMOTE_READ_TIMEOUT))

    fun revokeSession(server: IntentTraceServer, sessionToken: String) {
        send(HttpRequests.delete(server.currentSessionUri().toString(), null), sessionToken) { _, status ->
            when (status) {
                200, 401 -> Unit
                in 500..599 -> throw IntentTraceClientException("IntentTrace 또는 GitHub 연동이 일시적으로 응답하지 않습니다.")
                else -> throw IntentTraceClientException("IntentTrace 세션 폐기 요청이 거부됐습니다. HTTP $status")
            }
        }
    }

    private fun get(uri: URI, sessionToken: String?, sessionCheck: Boolean = false, readTimeout: Int = 10_000): String =
        send(HttpRequests.request(uri.toString()).accept("application/json"), sessionToken, readTimeout) { request, status ->
            when (status) {
                200 -> {
                    val bytes = request.inputStream.readNBytes(MAX_RESPONSE_BYTES + 1)
                    if (bytes.size > MAX_RESPONSE_BYTES) {
                        throw IntentTraceClientException("IntentTrace 조회 응답이 허용 크기를 초과했습니다.")
                    }
                    bytes.toString(StandardCharsets.UTF_8)
                }
                else -> throw IntentTraceClientException(when {
                    // Spring 상태 확인은 UP이 아니면 503으로 응답한다.
                    sessionToken == null && status == 503 -> "IntentTrace 서버가 정상 상태(UP)가 아닙니다. HTTP 503"
                    sessionToken == null -> "IntentTrace 서버 상태 확인 요청이 거부됐습니다. HTTP $status"
                    status == 400 -> "IntentTrace가 조회 조건을 거부했습니다. 검색어 길이와 파일 경로·커밋 형식을 확인해 주세요."
                    status == 401 -> "세션이 만료됐습니다. GitHub에 다시 로그인하고 새 세션을 연결해 주세요."
                    // 기록 ID 조회의 권한 없음은 서버가 404로 숨기므로 403은 저장소 단위 거부다.
                    status == 403 -> if (sessionCheck) "로그인 정보를 확인할 권한이 없습니다."
                        else "현재 GitHub 사용자는 이 저장소의 기록을 조회할 권한이 없습니다."
                    status == 404 -> "해당 IntentTrace 기록을 찾을 수 없습니다."
                    status in 500..599 -> "IntentTrace 또는 GitHub 연동이 일시적으로 응답하지 않습니다."
                    else -> "IntentTrace 조회 요청이 거부됐습니다. HTTP $status"
                })
            }
        }

    /** 세션 형식 검사·연결 제한·redirect 금지·세션 헤더·호출 제한 안내를 모든 요청에 같게 적용한다. 오류 본문은 읽지 않는다. */
    private fun <T> send(builder: RequestBuilder, sessionToken: String?, readTimeout: Int = 10_000,
        onStatus: (HttpRequests.Request, Int) -> T): T {
        // 형식이 틀린 값(GitHub 토큰 등)은 연결 전에 거부해 서버로 보내지 않는다.
        sessionToken?.let(::requireSessionToken)
        return try {
            builder.connectTimeout(5_000)
                .readTimeout(readTimeout)
                .followRedirects(false)
                .throwStatusCodeException(false)
                .tuner { connection -> sessionToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") } }
                .connect { request ->
                    val status = (request.connection as HttpURLConnection).responseCode
                    if (status == 429) throw IntentTraceRateLimitException(rateLimitMessage(request.connection.getHeaderField("Retry-After")))
                    onStatus(request, status)
                }
        } catch (_: SocketTimeoutException) {
            throw IntentTraceClientException("IntentTrace 서버의 응답 대기 시간을 초과했습니다.")
        } catch (_: IOException) {
            throw IntentTraceClientException("IntentTrace 서버에 연결하지 못했습니다.")
        }
    }

    private fun rateLimitMessage(retryAfter: String?): String {
        val seconds = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        val guidance = seconds?.let { "${it}초 후 다시 시도해 주세요." }
            ?: "대기 시간을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
        return "호출 제한에 도달했습니다. $guidance"
    }

    companion object {
        const val TOKEN_ENV = "INTENT_TRACE_SESSION_TOKEN"
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val REMOTE_READ_TIMEOUT = 40_000
        private val SESSION_TOKEN = Regex("^its_[A-Za-z0-9_-]{43}$")

        fun validSessionToken(value: String): Boolean = SESSION_TOKEN.matches(value)

        fun requireSessionToken(value: String) {
            if (!validSessionToken(value)) {
                throw IntentTraceUsageException("로그인 완료 화면에서 받은 its_ 세션 토큰을 입력하세요.")
            }
        }
    }
}

internal open class IntentTraceClientException(message: String) : IntentTraceUserException(message)

/** 서버가 응답했으므로 대기 후 다시 시도할 실패다. 로컬 세션 삭제를 제안하지 않는다. */
internal class IntentTraceRateLimitException(message: String) : IntentTraceClientException(message)

@Serializable
private data class HealthResponse(val status: String)

@Serializable
private data class LoginResponse(val actor: CreatedByResponse)
