package io.intenttrace.intellij

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal val responseJson = Json { ignoreUnknownKeys = true }

/** 응답 원문을 포함할 수 있는 원인 예외 대신 고정 문구만 전달한다. */
internal inline fun <reified T> decodeResponse(body: String): T = try {
    responseJson.decodeFromString<T>(body)
} catch (_: SerializationException) {
    throw IntentTraceClientException("IntentTrace 조회 응답 형식을 확인할 수 없습니다.")
}
