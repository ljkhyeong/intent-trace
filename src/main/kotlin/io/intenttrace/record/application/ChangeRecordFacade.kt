package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordContent
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.GitRevision
import io.intenttrace.record.domain.VerificationRun
import io.intenttrace.record.domain.requireRepositoryRelativePath
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

@Service
class ChangeRecordFacade(
    private val repository: ChangeRecordRepository,
    private val redactor: SensitiveTextRedactor,
    private val clock: Clock,
    private val meters: MeterRegistry = SimpleMeterRegistry(),
) {
    fun create(command: CreateChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        validateCreate(command)
        val repositoryKey = GitHubRepository.parse(command.repositoryKey).key
        val content = normalize(command)
        val creationDigest = content.digest()
        repository.findByRequestId(command.requestId)?.let {
            return reuseExisting(it, repositoryKey, actor, command.requestId, creationDigest)
        }

        val now = Instant.now(clock)
        val record = ChangeRecord(
            id = UUID.randomUUID(),
            requestId = command.requestId,
            repositoryKey = repositoryKey,
            baseRevision = content.baseRevision,
            targetRevision = null,
            snapshotDigest = content.snapshotDigest,
            title = content.title,
            requestSummary = content.requestSummary,
            status = ChangeRecordStatus.DRAFT,
            createdBy = actor,
            createdAt = now,
            confirmedAt = null,
            publishedAt = null,
            supersededBy = null,
            version = 0,
            decisions = content.decisions,
            codeAnchors = content.codeAnchors,
            verifications = content.verifications,
            openQuestions = content.openQuestions,
            creationDigest = creationDigest,
            derivedFromRecordId = command.derivedFromRecordId,
        )

        return try {
            repository.saveNew(record).also { measured("create") }
        } catch (exception: DuplicateKeyException) {
            val existing = repository.findByRequestId(command.requestId) ?: throw exception
            reuseExisting(existing, repositoryKey, actor, command.requestId, creationDigest)
        }
    }

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
        return saveChange(current, confirmed, actor, RecordOperation.CONFIRM)
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
        return saveChange(current, published, actor, RecordOperation.PUBLISH)
    }

    fun supersede(command: SupersedeChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        return supersede(get(command.recordId), get(command.replacementRecordId), command, actor)
    }

    fun supersede(
        current: ChangeRecord,
        replacement: ChangeRecord,
        command: SupersedeChangeRecordCommand,
        actor: ActorIdentity,
    ): ChangeRecord {
        require(current.id == command.recordId && replacement.id == command.replacementRecordId) {
            "대체 명령과 변경 의도 기록이 일치하지 않습니다."
        }
        requireExpectedVersion(current, command.expectedVersion)
        return saveChange(current, current.supersede(actor, replacement), actor, RecordOperation.SUPERSEDE)
    }

    fun revise(current: ChangeRecord, expectedVersion: Long, command: CreateChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        require(command.requestId == current.requestId && GitHubRepository.parse(command.repositoryKey).key == current.repositoryKey) {
            "초안 수정으로 요청 ID나 저장소를 바꿀 수 없습니다."
        }
        validateCreate(command)
        requireExpectedVersion(current, expectedVersion)
        return saveChange(current, current.revise(actor, normalize(command)), actor, RecordOperation.REVISE)
    }

    fun reopen(current: ChangeRecord, expectedVersion: Long, actor: ActorIdentity): ChangeRecord {
        requireExpectedVersion(current, expectedVersion)
        return saveChange(current, current.reopen(actor), actor, RecordOperation.REOPEN)
    }

    fun discard(current: ChangeRecord, expectedVersion: Long, actor: ActorIdentity): ChangeRecord {
        requireExpectedVersion(current, expectedVersion)
        return saveChange(current, current.discard(actor), actor, RecordOperation.DISCARD)
    }

    private fun saveChange(previous: ChangeRecord, next: ChangeRecord, actor: ActorIdentity, operation: RecordOperation): ChangeRecord =
        repository.update(next, previous.version, RecordActivity(next.id, operation, actor.subject,
            previous.version, next.version, previous.status, next.status, Instant.now(clock)))
            .also { measured(operation.name.lowercase()) }

    private fun measured(operation: String) {
        meters.counter("intenttrace.record.operation", "operation", operation).increment()
    }

    private fun normalize(command: CreateChangeRecordCommand): ChangeRecordContent = ChangeRecordContent(
        baseRevision = command.baseRevision?.let { GitRevision.parse(it).value },
        snapshotDigest = command.snapshotDigest.lowercase(),
        title = redactor.redact(command.title),
        requestSummary = redactor.redact(command.requestSummary),
        decisions = command.decisions.map(::redact),
        codeAnchors = command.codeAnchors.map(::normalize),
        verifications = command.verifications.map(::redact),
        openQuestions = command.openQuestions.map(redactor::redact),
        derivedFromRecordId = command.derivedFromRecordId,
    )

    fun findIntent(repositoryKey: String, revision: String, path: String, line: Int): List<ChangeRecord> {
        val normalizedRepositoryKey = GitHubRepository.parse(repositoryKey).key
        val normalizedRevision = GitRevision.parse(revision).value
        requireRepositoryRelativePath(path)
        require(line > 0) { "코드 줄 번호는 1 이상이어야 합니다." }

        return repository.findPublishedByAnchor(normalizedRepositoryKey, normalizedRevision, path, line)
    }

    private fun validateCreate(command: CreateChangeRecordCommand) {
        require(command.requestId.isNotBlank()) { "요청 식별자는 비어 있을 수 없습니다." }
        require(command.repositoryKey.isNotBlank()) { "저장소 식별자는 비어 있을 수 없습니다." }
        require(command.title.isNotBlank()) { "제목은 비어 있을 수 없습니다." }
        require(command.requestSummary.isNotBlank()) { "요청 요약은 비어 있을 수 없습니다." }
        require(SHA_256.matches(command.snapshotDigest)) { "기록에는 SHA-256 스냅샷 해시가 필요합니다." }
        require(command.decisions.isNotEmpty()) { "구현 결정을 1개 이상 입력하세요." }
        require(command.codeAnchors.isNotEmpty()) { "관련 코드를 1개 이상 입력하세요." }
        require(command.baseRevision != null || command.codeAnchors.none { it.side == CodeSide.BASE }) {
            "변경 전 관련 코드에는 변경 전 커밋 해시(전체 길이)가 필요합니다."
        }
        command.codeAnchors.forEach { anchor ->
            anchor.relatedPath?.let { related ->
                require(command.codeAnchors.any { it.side != anchor.side && it.relativePath == related }) {
                    "이름 변경의 연결 경로는 반대쪽 관련 코드에 있어야 합니다."
                }
            }
        }
    }

    private fun requireExpectedVersion(record: ChangeRecord, expectedVersion: Long) {
        if (record.version != expectedVersion) {
            throw ConcurrentChangeRecordUpdateException(record.id)
        }
    }

    private fun reuseExisting(
        existing: ChangeRecord,
        repositoryKey: String,
        actor: ActorIdentity,
        requestId: String,
        creationDigest: String,
    ): ChangeRecord {
        if (GitHubRepository.parse(existing.repositoryKey).key != repositoryKey || existing.createdBy.subject != actor.subject) {
            throw ChangeRecordRequestConflictException(requestId)
        }
        if ((existing.creationDigest ?: existing.content().digest()) != creationDigest) {
            throw ChangeRecordRequestConflictException(requestId)
        }
        return existing
    }

    private fun redact(decision: Decision): Decision = decision.copy(
        summary = redactor.redact(decision.summary),
        rationale = redactor.redactNullable(decision.rationale),
    )

    private fun normalize(anchor: CodeAnchor): CodeAnchor = anchor.copy(
        contentHash = anchor.contentHash.lowercase(),
        symbolName = redactor.redactNullable(anchor.symbolName),
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
