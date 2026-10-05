package io.intenttrace.intellij

import kotlinx.serialization.Serializable

@Serializable
internal data class ChangeIntentRecord(
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
    val baseRevision: String? = null,
    val derivedFromRecordId: String? = null,
) {
    fun revisionFor(anchor: ChangeCodeAnchor): String? = when (anchor.side) {
        CodeSide.BASE -> baseRevision
        CodeSide.TARGET -> targetRevision
    }
}

/** 현재 줄 조회 결과다. 서버가 최근 공개 순 20건까지 돌려주고 더 있으면 [truncated]다. */
@Serializable
internal data class ChangeIntentLookup(val items: List<ChangeIntentRecord>, val truncated: Boolean)

internal enum class RecordListScope { TEAM, MINE }

internal data class RecordListQuery(
    val repositoryKey: String,
    val scope: RecordListScope = RecordListScope.TEAM,
    val path: String? = null,
    val status: String? = null,
    val cursor: String? = null,
    val keyword: String? = null,
)

@Serializable
internal data class ChangeRecordPage(
    val items: List<ChangeRecordSummary>,
    val nextCursor: String?,
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
    val source: String? = null,
)

@Serializable
internal data class ConnectionDiagnosis(
    val repositoryKey: String,
    val checkedAt: String,
    val checks: List<ConnectionCheck>,
)

@Serializable
internal data class ConnectionCheck(val name: String, val status: String, val message: String = "")

@Serializable
internal data class ChangeIntentHistory(
    val queryRevision: String,
    val path: String,
    val items: List<HistoricalIntent>,
    val nextCursor: String? = null,
    val scannedRecords: Int,
    val failures: List<HistoryFailure> = emptyList(),
    val stopReason: String? = null,
    val complete: Boolean = true,
    val resumeBlocked: Boolean = false,
)

@Serializable
internal data class HistoricalIntent(
    val record: ChangeRecordSummary,
    val sourceRevision: String,
    val side: CodeSide = CodeSide.TARGET,
    val match: String,
    val verificationAppliesToQuery: Boolean,
    val sourcePath: String,
    val sourceStartLine: Int,
    val sourceEndLine: Int,
    val currentStartLine: Int? = null,
    val currentEndLine: Int? = null,
)

@Serializable
internal data class HistoryFailure(val recordId: String, val reason: String)
