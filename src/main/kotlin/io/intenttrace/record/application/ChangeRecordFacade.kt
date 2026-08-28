package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.VerificationRun
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class ChangeRecordFacade(
    private val repository: ChangeRecordRepository,
    private val redactor: SensitiveTextRedactor,
    private val clock: Clock,
) {
    fun create(command: CreateChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        repository.findByRequestId(command.requestId)?.let {
            if (it.repositoryKey != command.repositoryKey || it.createdBy.subject != actor.subject) {
                throw ChangeRecordRequestConflictException(command.requestId)
            }
            return it
        }
        validateCreate(command)

        val now = Instant.now(clock)
        val record = ChangeRecord(
            id = UUID.randomUUID(),
            requestId = command.requestId,
            repositoryKey = command.repositoryKey,
            baseRevision = command.baseRevision?.lowercase(),
            targetRevision = null,
            snapshotDigest = command.snapshotDigest.lowercase(),
            title = redactor.redact(command.title),
            requestSummary = redactor.redact(command.requestSummary),
            status = ChangeRecordStatus.DRAFT,
            createdBy = actor,
            createdAt = now,
            confirmedAt = null,
            publishedAt = null,
            supersededBy = null,
            version = 0,
            decisions = command.decisions.map(::redact),
            codeAnchors = command.codeAnchors.map(::normalize),
            verifications = command.verifications.map(::redact),
            openQuestions = command.openQuestions.map(redactor::redact),
        )

        return repository.saveNew(record)
    }

    fun get(id: UUID): ChangeRecord = repository.findById(id)
        ?: throw ChangeRecordNotFoundException(id)

    fun confirm(command: ConfirmChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        val current = get(command.recordId)
        requireExpectedVersion(current, command.expectedVersion)
        val confirmed = current.confirm(
            actor = actor,
            immutableRevision = command.immutableRevision,
            currentSnapshotDigest = command.currentSnapshotDigest.lowercase(),
            now = Instant.now(clock),
        )
        return repository.update(confirmed, command.expectedVersion)
    }

    fun publish(command: PublishChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        val current = get(command.recordId)
        requireExpectedVersion(current, command.expectedVersion)
        val published = current.publish(
            actor = actor,
            currentSnapshotDigest = command.currentSnapshotDigest.lowercase(),
            now = Instant.now(clock),
        )
        return repository.update(published, command.expectedVersion)
    }

    fun supersede(command: SupersedeChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        val current = get(command.recordId)
        requireExpectedVersion(current, command.expectedVersion)
        val replacement = get(command.replacementRecordId)
        return repository.update(current.supersede(actor, replacement), command.expectedVersion)
    }

    fun findIntent(repositoryKey: String, revision: String, path: String, line: Int): List<ChangeRecord> {
        require(repositoryKey.isNotBlank()) { "저장소 식별자는 비어 있을 수 없습니다." }
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains("..")) {
            "코드 경로는 저장소 기준 상대 경로여야 합니다."
        }
        require(line > 0) { "코드 줄 번호는 1 이상이어야 합니다." }

        return repository.findPublished(repositoryKey, revision.lowercase())
            .filter { it.contains(path, line) }
    }

    private fun validateCreate(command: CreateChangeRecordCommand) {
        require(command.requestId.isNotBlank()) { "요청 식별자는 비어 있을 수 없습니다." }
        require(command.repositoryKey.isNotBlank()) { "저장소 식별자는 비어 있을 수 없습니다." }
        require(command.title.isNotBlank()) { "제목은 비어 있을 수 없습니다." }
        require(command.requestSummary.isNotBlank()) { "요청 요약은 비어 있을 수 없습니다." }
        require(SHA_256.matches(command.snapshotDigest)) { "기록에는 SHA-256 스냅샷 해시가 필요합니다." }
        require(command.decisions.isNotEmpty()) { "최소 한 개의 판단이 필요합니다." }
        require(command.codeAnchors.isNotEmpty()) { "최소 한 개의 코드 근거가 필요합니다." }
    }

    private fun requireExpectedVersion(record: ChangeRecord, expectedVersion: Long) {
        if (record.version != expectedVersion) {
            throw ConcurrentChangeRecordUpdateException(record.id)
        }
    }

    private fun redact(decision: Decision): Decision = decision.copy(
        summary = redactor.redact(decision.summary),
        rationale = redactor.redactNullable(decision.rationale),
    )

    private fun normalize(anchor: CodeAnchor): CodeAnchor = anchor.copy(
        contentHash = anchor.contentHash.lowercase(),
    )

    private fun redact(verification: VerificationRun): VerificationRun = verification.copy(
        command = redactor.redact(verification.command),
        snapshotDigest = verification.snapshotDigest.lowercase(),
        outputDigest = verification.outputDigest.lowercase(),
        summary = redactor.redact(verification.summary),
    )

    companion object {
        private val SHA_256 = Regex("^[0-9a-fA-F]{64}$")
    }
}
