package io.intenttrace.intellij

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal object IntentTraceResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<ChangeIntentRecord> = try {
        json.decodeFromString<List<ChangeIntentResponse>>(body).map(ChangeIntentResponse::toRecord)
    } catch (exception: SerializationException) {
        throw IntentTraceClientException("IntentTrace 조회 응답 형식을 확인할 수 없습니다.", exception)
    }
}

@Serializable
private data class ChangeIntentResponse(
    val id: String,
    val title: String,
    val requestSummary: String,
    val status: String,
    val createdBy: CreatedByResponse,
    val decisions: List<ChangeDecision>,
    val codeAnchors: List<ChangeCodeAnchor>,
    val verifications: List<ChangeVerification>,
    val openQuestions: List<String>,
) {
    fun toRecord(): ChangeIntentRecord = ChangeIntentRecord(
        id = id,
        title = title,
        requestSummary = requestSummary,
        status = status,
        authorLogin = createdBy.login,
        decisions = decisions,
        codeAnchors = codeAnchors,
        verifications = verifications,
        openQuestions = openQuestions,
    )
}

@Serializable
private data class CreatedByResponse(val login: String)
