package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.GitRevision
import io.intenttrace.record.domain.VerificationRun
import io.intenttrace.record.domain.requireRepositoryRelativePath
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class ChangeRecordFacade(
    private val repository: ChangeRecordRepository,
    private val redactor: SensitiveTextRedactor,
    private val clock: Clock,
) {
    fun create(command: CreateChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        validateCreate(command)
        val repositoryKey = GitHubRepository.parse(command.repositoryKey).key
        val candidate = newRecord(command, repositoryKey, actor)
        repository.findByRequestId(command.requestId)?.let {
            return reuseExisting(it, candidate)
        }

        return try {
            repository.saveNew(candidate)
        } catch (exception: DuplicateKeyException) {
            val existing = repository.findByRequestId(command.requestId) ?: throw exception
            reuseExisting(existing, candidate)
        }
    }

    private fun newRecord(
        command: CreateChangeRecordCommand,
        repositoryKey: String,
        actor: ActorIdentity,
    ): ChangeRecord =
        ChangeRecord(
            id = UUID.randomUUID(),
            requestId = command.requestId,
            repositoryKey = repositoryKey,
            targetRevision = null,
            snapshotDigest = command.snapshotDigest.lowercase(),
            title = redact(command.title, 200, "제목"),
            requestSummary = redact(command.requestSummary, 2000, "요청 요약"),
            status = ChangeRecordStatus.DRAFT,
            createdBy = actor,
            createdAt = Instant.now(clock),
            confirmedAt = null,
            publishedAt = null,
            supersededBy = null,
            version = 0,
            decisions = command.decisions.map(::redact),
            codeAnchors = command.codeAnchors.map(::normalize),
            verifications = command.verifications.map(::normalize),
            openQuestions = command.openQuestions.map { redact(it, 1000, "남은 질문") },
        )

    fun get(id: UUID): ChangeRecord = repository.findById(id)
        ?: throw ChangeRecordNotFoundException(id)

    fun confirm(command: ConfirmChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        return confirm(get(command.recordId), command, actor)
    }

    fun confirm(current: ChangeRecord, command: ConfirmChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        require(current.id == command.recordId) { "확인 명령과 변경 의도 기록이 일치하지 않습니다." }
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
        return publish(get(command.recordId), command, actor)
    }

    fun publish(current: ChangeRecord, command: PublishChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        require(current.id == command.recordId) { "공개 명령과 변경 의도 기록이 일치하지 않습니다." }
        requireExpectedVersion(current, command.expectedVersion)
        val published = current.publish(
            actor = actor,
            currentSnapshotDigest = command.currentSnapshotDigest.lowercase(),
            now = Instant.now(clock),
        )
        return repository.update(published, command.expectedVersion)
    }

    @Transactional
    fun supersede(command: SupersedeChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        val records = repository.findByIdsForUpdate(setOf(command.recordId, command.replacementRecordId))
            .associateBy(ChangeRecord::id)
        val current = records[command.recordId] ?: throw ChangeRecordNotFoundException(command.recordId)
        val replacement = records[command.replacementRecordId]
            ?: throw ChangeRecordNotFoundException(command.replacementRecordId)
        requireExpectedVersion(current, command.expectedVersion)
        return repository.update(current.supersede(actor, replacement), command.expectedVersion)
    }

    fun findIntent(repositoryKey: String, revision: String, path: String, line: Int): List<ChangeRecord> {
        val normalizedRepositoryKey = GitHubRepository.parse(repositoryKey).key
        val normalizedRevision = GitRevision.parse(revision).value
        val normalizedPath = requireRepositoryRelativePath(path)
        require(line > 0) { "코드 줄 번호는 1 이상이어야 합니다." }

        return repository.findPublishedByAnchor(normalizedRepositoryKey, normalizedRevision, normalizedPath, line)
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

    private fun reuseExisting(
        existing: ChangeRecord,
        candidate: ChangeRecord,
    ): ChangeRecord {
        if (
            GitHubRepository.parse(existing.repositoryKey).key != candidate.repositoryKey ||
            existing.createdBy.subject != candidate.createdBy.subject ||
            !existing.hasSameCreatePayload(candidate)
        ) {
            throw ChangeRecordRequestConflictException(candidate.requestId)
        }
        return existing
    }

    private fun ChangeRecord.hasSameCreatePayload(other: ChangeRecord): Boolean =
        snapshotDigest == other.snapshotDigest &&
            title == other.title &&
            requestSummary == other.requestSummary &&
            decisions == other.decisions &&
            codeAnchors == other.codeAnchors &&
            verifications == other.verifications &&
            openQuestions == other.openQuestions

    private fun redact(decision: Decision): Decision = decision.copy(
        summary = redact(decision.summary, 1000, "판단 요약"),
        rationale = decision.rationale?.let { redact(it, 2000, "판단 근거") },
    )

    private fun normalize(anchor: CodeAnchor): CodeAnchor = anchor.copy(
        relativePath = requireRepositoryRelativePath(anchor.relativePath),
        symbolName = anchor.symbolName?.let { redact(it, 500, "코드 심벌 이름") },
        contentHash = anchor.contentHash.lowercase(),
    )

    private fun normalize(verification: VerificationRun): VerificationRun = verification.copy(
        command = redact(verification.command, 2000, "검증 명령"),
        startedAt = verification.startedAt.plusNanos(500).truncatedTo(ChronoUnit.MICROS),
        finishedAt = verification.finishedAt.plusNanos(500).truncatedTo(ChronoUnit.MICROS),
        snapshotDigest = verification.snapshotDigest.lowercase(),
        outputDigest = verification.outputDigest.lowercase(),
        summary = redact(verification.summary, 2000, "검증 요약"),
    )

    private fun redact(value: String, maxLength: Int, field: String): String = redactor.redact(value).also {
        require(it.length <= maxLength) { "비밀값 제거 후 $field 길이는 ${maxLength}자 이하여야 합니다." }
    }

    companion object {
        private val SHA_256 = Regex("^[0-9a-fA-F]{64}$")
    }
}
