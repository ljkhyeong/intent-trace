package io.intenttrace.intellij

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

internal data class ChangeDecision(
    val summary: String,
    val rationale: String?,
    val source: String,
)

internal data class ChangeCodeAnchor(
    val relativePath: String,
    val startLine: Int,
    val endLine: Int,
)

internal data class ChangeVerification(
    val command: String,
    val exitCode: Int,
    val summary: String,
    val current: Boolean,
)
