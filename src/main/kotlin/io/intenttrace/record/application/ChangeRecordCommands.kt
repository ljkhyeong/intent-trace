package io.intenttrace.record.application

import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.VerificationRun
import java.util.UUID

data class CreateChangeRecordCommand(
    val requestId: String,
    val repositoryKey: String,
    val baseRevision: String? = null,
    val snapshotDigest: String,
    val title: String,
    val requestSummary: String,
    val decisions: List<Decision>,
    val codeAnchors: List<CodeAnchor>,
    val verifications: List<VerificationRun>,
    val openQuestions: List<String>,
    val derivedFromRecordId: UUID? = null,
)

data class ConfirmChangeRecordCommand(
    val recordId: UUID,
    val expectedVersion: Long,
    val immutableRevision: String,
    val currentSnapshotDigest: String,
)

data class PublishChangeRecordCommand(
    val recordId: UUID,
    val expectedVersion: Long,
    val currentSnapshotDigest: String,
)

data class SupersedeChangeRecordCommand(
    val recordId: UUID,
    val expectedVersion: Long,
    val replacementRecordId: UUID,
)


data class SuccessorDraftCommand(
    val requestId: String,
    val baseRevision: String?,
    val snapshotDigest: String,
    val codeAnchors: List<CodeAnchor>,
)
