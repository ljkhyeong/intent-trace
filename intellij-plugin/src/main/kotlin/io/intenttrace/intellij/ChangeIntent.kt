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
    val repositoryKey: String,
    val targetRevision: String?,
    val supersededBy: String?,
    val baseRevision: String? = null,
) {
    fun revisionFor(anchor: ChangeCodeAnchor): String? = when (anchor.side) {
        CodeSide.BASE -> baseRevision
        CodeSide.TARGET -> targetRevision
    }
}

internal enum class RecordListScope(private val label: String) {
    TEAM("팀 공개 기록"),
    MY_DRAFTS("내 비공개 기록");

    override fun toString(): String = label
}

internal data class RecordListQuery(
    val repositoryKey: String,
    val scope: RecordListScope = RecordListScope.TEAM,
    val path: String? = null,
    val status: String? = null,
    val page: Int = 0,
)

@Serializable
internal data class ChangeRecordPage(
    val items: List<ChangeRecordSummary>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean,
)

@Serializable
internal data class ChangeRecordSummary(
    val id: String,
    val title: String,
    val status: String,
    val targetRevision: String?,
    val createdBy: CreatedByResponse,
    val createdAt: String,
)

@Serializable
internal data class CreatedByResponse(val login: String)

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
    val side: CodeSide = CodeSide.TARGET,
) {
    val label: String get() = "[${side.label}] $relativePath:$startLine-$endLine"
}

@Serializable
internal enum class CodeSide(val label: String) { BASE("변경 전"), TARGET("변경 후") }

@Serializable
internal data class ChangeVerification(
    val command: String,
    val exitCode: Int,
    val summary: String,
    val current: Boolean,
)
