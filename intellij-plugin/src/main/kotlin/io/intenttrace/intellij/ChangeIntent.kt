package io.intenttrace.intellij

import kotlinx.serialization.Serializable

internal data class ChangeIntentRecord(
    val id: String,
    val title: String,
    val requestSummary: String,
    val status: String,
    val authorLogin: String,
    val decisions: List<ChangeDecision>,
    val codeAnchors: List<ChangeCodeAnchor>,
    val verifications: List<ChangeVerification>,
    val openQuestions: List<String>,
)

@Serializable
internal data class ChangeDecision(
    val summary: String,
    val rationale: String? = null,
    val source: String,
)

@Serializable
internal data class ChangeCodeAnchor(
    val relativePath: String,
    val startLine: Int,
    val endLine: Int,
)

@Serializable
internal data class ChangeVerification(
    val command: String,
    val exitCode: Int,
    val summary: String,
    val current: Boolean,
)
