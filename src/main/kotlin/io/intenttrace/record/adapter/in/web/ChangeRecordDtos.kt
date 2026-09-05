package io.intenttrace.record.adapter.`in`.web

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.ConfirmChangeRecordCommand
import io.intenttrace.record.application.CreateChangeRecordCommand
import io.intenttrace.record.application.PublishChangeRecordCommand
import io.intenttrace.record.application.SupersedeChangeRecordCommand
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.FULL_GIT_REVISION_PATTERN
import io.intenttrace.record.domain.PurposeSource
import io.intenttrace.record.domain.VerificationRun
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateChangeRecordRequest(
    @field:NotBlank @field:Size(max = 120)
    val requestId: String,
    @field:NotBlank @field:Size(max = 255)
    val repositoryKey: String,
    @field:Pattern(regexp = FULL_GIT_REVISION_PATTERN)
    val baseRevision: String? = null,
    @field:Pattern(regexp = "^[0-9a-fA-F]{64}$")
    val snapshotDigest: String,
    @field:NotBlank @field:Size(max = 200)
    val title: String,
    @field:NotBlank @field:Size(max = 2000)
    val requestSummary: String,
    @field:NotEmpty @field:Size(max = 20)
    val decisions: List<@Valid DecisionRequest>,
    @field:NotEmpty @field:Size(max = 100)
    val codeAnchors: List<@Valid CodeAnchorRequest>,
    @field:Size(max = 50)
    val verifications: List<@Valid VerificationRequest> = emptyList(),
    @field:Size(max = 50)
    val openQuestions: List<@NotBlank @Size(max = 1000) String> = emptyList(),
) {
    fun toCommand(): CreateChangeRecordCommand = CreateChangeRecordCommand(
        requestId = requestId,
        repositoryKey = repositoryKey,
        baseRevision = baseRevision,
        snapshotDigest = snapshotDigest,
        title = title,
        requestSummary = requestSummary,
        decisions = decisions.map(DecisionRequest::toDomain),
        codeAnchors = codeAnchors.map(CodeAnchorRequest::toDomain),
        verifications = verifications.map(VerificationRequest::toDomain),
        openQuestions = openQuestions,
    )
}

data class ReviseChangeRecordRequest(
    @field:Min(0) val expectedVersion: Long,
    @field:Valid val content: CreateChangeRecordRequest,
)

data class RecordVersionRequest(@field:Min(0) val expectedVersion: Long)

data class DecisionRequest(
    @field:NotBlank @field:Size(max = 1000)
    val summary: String,
    @field:Size(max = 2000)
    val rationale: String? = null,
    val source: PurposeSource,
) {
    fun toDomain(): Decision = Decision(summary, rationale, source)
}

data class CodeAnchorRequest(
    @field:NotBlank @field:Size(max = 1000)
    val relativePath: String,
    @field:Size(max = 500)
    val symbolName: String? = null,
    @field:Min(1)
    val startLine: Int,
    @field:Min(1) @field:Max(10_000_000)
    val endLine: Int,
    @field:Pattern(regexp = "^[0-9a-fA-F]{64}$")
    val contentHash: String,
) {
    fun toDomain(): CodeAnchor = CodeAnchor(relativePath, symbolName, startLine, endLine, contentHash)
}

data class VerificationRequest(
    @field:NotBlank @field:Size(max = 2000)
    val command: String,
    val exitCode: Int,
    val startedAt: Instant,
    val finishedAt: Instant,
    @field:Pattern(regexp = "^[0-9a-fA-F]{64}$")
    val snapshotDigest: String,
    @field:Pattern(regexp = "^[0-9a-fA-F]{64}$")
    val outputDigest: String,
    @field:NotBlank @field:Size(max = 2000)
    val summary: String,
) {
    fun toDomain(): VerificationRun = VerificationRun(
        command,
        exitCode,
        startedAt,
        finishedAt,
        snapshotDigest,
        outputDigest,
        summary,
    )
}

data class ConfirmChangeRecordRequest(
    val expectedVersion: Long,
    @field:Pattern(regexp = FULL_GIT_REVISION_PATTERN)
    val immutableRevision: String,
    @field:Pattern(regexp = "^[0-9a-fA-F]{64}$")
    val currentSnapshotDigest: String,
) {
    fun toCommand(recordId: UUID): ConfirmChangeRecordCommand = ConfirmChangeRecordCommand(
        recordId,
        expectedVersion,
        immutableRevision,
        currentSnapshotDigest,
    )
}

data class PublishChangeRecordRequest(
    val expectedVersion: Long,
    @field:Pattern(regexp = "^[0-9a-fA-F]{64}$")
    val currentSnapshotDigest: String,
) {
    fun toCommand(recordId: UUID): PublishChangeRecordCommand =
        PublishChangeRecordCommand(recordId, expectedVersion, currentSnapshotDigest)
}

data class SupersedeChangeRecordRequest(
    val expectedVersion: Long,
    val replacementRecordId: UUID,
) {
    fun toCommand(recordId: UUID): SupersedeChangeRecordCommand =
        SupersedeChangeRecordCommand(recordId, expectedVersion, replacementRecordId)
}

data class ChangeRecordResponse(
    val id: UUID,
    val requestId: String,
    val repositoryKey: String,
    val baseRevision: String?,
    val targetRevision: String?,
    val snapshotDigest: String,
    val title: String,
    val requestSummary: String,
    val status: ChangeRecordStatus,
    val createdBy: ActorIdentity,
    val createdAt: Instant,
    val confirmedAt: Instant?,
    val publishedAt: Instant?,
    val supersededBy: UUID?,
    val version: Long,
    val decisions: List<Decision>,
    val codeAnchors: List<CodeAnchor>,
    val verifications: List<VerificationResponse>,
    val openQuestions: List<String>,
) {
    companion object {
        fun from(record: ChangeRecord): ChangeRecordResponse = ChangeRecordResponse(
            id = record.id,
            requestId = record.requestId,
            repositoryKey = record.repositoryKey,
            baseRevision = record.baseRevision,
            targetRevision = record.targetRevision,
            snapshotDigest = record.snapshotDigest,
            title = record.title,
            requestSummary = record.requestSummary,
            status = record.status,
            createdBy = record.createdBy,
            createdAt = record.createdAt,
            confirmedAt = record.confirmedAt,
            publishedAt = record.publishedAt,
            supersededBy = record.supersededBy,
            version = record.version,
            decisions = record.decisions,
            codeAnchors = record.codeAnchors,
            verifications = record.verifications.map {
                VerificationResponse(
                    command = it.command,
                    exitCode = it.exitCode,
                    startedAt = it.startedAt,
                    finishedAt = it.finishedAt,
                    snapshotDigest = it.snapshotDigest,
                    outputDigest = it.outputDigest,
                    summary = it.summary,
                    current = it.isCurrentFor(record),
                )
            },
            openQuestions = record.openQuestions,
        )
    }
}

data class VerificationResponse(
    val command: String,
    val exitCode: Int,
    val startedAt: Instant,
    val finishedAt: Instant,
    val snapshotDigest: String,
    val outputDigest: String,
    val summary: String,
    val current: Boolean,
)
