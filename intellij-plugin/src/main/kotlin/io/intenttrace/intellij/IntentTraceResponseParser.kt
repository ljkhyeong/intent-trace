package io.intenttrace.intellij

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object IntentTraceResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<ChangeIntentRecord> = try {
        json.parseToJsonElement(body).jsonArray.map(::record)
    } catch (exception: Exception) {
        throw IntentTraceClientException("IntentTrace 조회 응답 형식을 확인할 수 없습니다.", exception)
    }

    private fun record(element: JsonElement): ChangeIntentRecord {
        val value = element.jsonObject
        return ChangeIntentRecord(
            id = value.requiredString("id"),
            title = value.requiredString("title"),
            requestSummary = value.requiredString("requestSummary"),
            status = value.requiredString("status"),
            authorLogin = value.requiredObject("createdBy").requiredString("login"),
            decisions = value.array("decisions").map { decision(it.jsonObject) },
            codeAnchors = value.array("codeAnchors").map { anchor(it.jsonObject) },
            verifications = value.array("verifications").map { verification(it.jsonObject) },
            openQuestions = value.array("openQuestions").map { it.jsonPrimitive.content },
        )
    }

    private fun decision(value: JsonObject): ChangeDecision = ChangeDecision(
        summary = value.requiredString("summary"),
        rationale = value.optionalString("rationale"),
        source = value.requiredString("source"),
    )

    private fun anchor(value: JsonObject): ChangeCodeAnchor = ChangeCodeAnchor(
        relativePath = value.requiredString("relativePath"),
        startLine = value.requiredInt("startLine"),
        endLine = value.requiredInt("endLine"),
    )

    private fun verification(value: JsonObject): ChangeVerification = ChangeVerification(
        command = value.requiredString("command"),
        exitCode = value.requiredInt("exitCode"),
        summary = value.requiredString("summary"),
        current = value.requiredBoolean("current"),
    )

    private fun JsonObject.requiredObject(name: String): JsonObject =
        get(name)?.jsonObject ?: error("$name object가 없습니다.")

    private fun JsonObject.array(name: String): JsonArray =
        get(name)?.jsonArray ?: error("$name array가 없습니다.")

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.jsonPrimitive?.contentOrNull ?: error("$name 문자열이 없습니다.")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.jsonPrimitive?.intOrNull ?: error("$name 정수가 없습니다.")

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        get(name)?.jsonPrimitive?.booleanOrNull ?: error("$name boolean이 없습니다.")
}
