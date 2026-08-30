package io.intenttrace.intellij

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal object IntentTraceResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<ChangeIntentRecord> = decode<List<ChangeIntentResponse>>(body)
        .map(ChangeIntentResponse::toRecord)

    fun parseRecord(body: String): ChangeIntentRecord = decode<ChangeIntentResponse>(body).toRecord()

    fun parsePage(body: String): ChangeRecordPage = decode(body)

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (_: SerializationException) {
        throw IntentTraceClientException("IntentTrace 조회 응답 형식을 확인할 수 없습니다.")
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
    val repositoryKey: String,
    val targetRevision: String?,
    val supersededBy: String? = null,
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
        repositoryKey = repositoryKey,
        targetRevision = targetRevision,
        supersededBy = supersededBy,
    )
}
