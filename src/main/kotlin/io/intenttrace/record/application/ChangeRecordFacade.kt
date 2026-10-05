package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordContent
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.requireFullRevision
import io.intenttrace.record.domain.SHA_256
import io.intenttrace.record.domain.VerificationRun
import io.intenttrace.record.domain.requireRepositoryRelativePath
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import io.micrometer.core.instrument.MeterRegistry

@Service
class ChangeRecordFacade(
    private val repository: ChangeRecordRepository,
    private val redactor: SensitiveTextRedactor,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    fun create(command: CreateChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        validateCreate(command)
        val repositoryKey = GitHubRepository.parse(command.repositoryKey).key
        val content = normalize(command)
        val creationDigest = content.digest()
        repository.findByRequestId(command.requestId)?.let {
            return reuseExisting(it, repositoryKey, actor, creationDigest)
        }

        val now = recordTime()
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
            reuseExisting(existing, repositoryKey, actor, creationDigest)
        }
    }

    fun get(id: UUID): ChangeRecord = repository.findById(id)
        ?: throw ChangeRecordNotFoundException(id)

    fun confirm(current: ChangeRecord, command: ConfirmChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        requireExpectedVersion(current, command.expectedVersion)
        val confirmed = current.confirm(
            actor = actor,
            immutableRevision = command.immutableRevision,
            currentSnapshotDigest = command.currentSnapshotDigest.lowercase(),
            now = recordTime(),
        )
        return saveChange(current, confirmed, actor, RecordOperation.CONFIRM)
    }

    fun publish(current: ChangeRecord, command: PublishChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        requireExpectedVersion(current, command.expectedVersion)
        val published = current.publish(
            actor = actor,
            currentSnapshotDigest = command.currentSnapshotDigest.lowercase(),
            now = recordTime(),
        )
        return saveChange(current, published, actor, RecordOperation.PUBLISH)
    }

    @Transactional
    fun supersede(command: SupersedeChangeRecordCommand, actor: ActorIdentity): ChangeRecord {
        val records = repository.findByIdsForUpdate(setOf(command.recordId, command.replacementRecordId))
            .associateBy(ChangeRecord::id)
        val current = records[command.recordId] ?: throw ChangeRecordNotFoundException(command.recordId)
        val replacement = records[command.replacementRecordId]
            ?: throw ChangeRecordNotFoundException(command.replacementRecordId)
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
            previous.version, next.version, previous.status, next.status, recordTime()))
            .also { measured(operation.name.lowercase()) }

    private fun recordTime(): Instant = Instant.now(clock).plusNanos(500).truncatedTo(ChronoUnit.MICROS)

    private fun measured(operation: String) {
        meters.counter("intenttrace.record.operation", "operation", operation).increment()
    }

    private fun normalize(command: CreateChangeRecordCommand): ChangeRecordContent = ChangeRecordContent(
        baseRevision = command.baseRevision?.let { requireFullRevision(it) },
        snapshotDigest = command.snapshotDigest.lowercase(),
        title = redact(command.title, 200, "제목"),
        requestSummary = redact(command.requestSummary, 2000, "요청 요약"),
        decisions = command.decisions.map(::redact),
        codeAnchors = command.codeAnchors.map(::normalize),
        verifications = command.verifications.map(::normalize),
        openQuestions = command.openQuestions.map { redact(it, 1000, "남은 질문") },
        derivedFromRecordId = command.derivedFromRecordId,
    ).also { content ->
        content.codeAnchors.forEach { anchor ->
            anchor.relatedPath?.let { related ->
                require(content.codeAnchors.any { it.side != anchor.side && it.relativePath == related }) {
                    "relatedPath는 다른 side(BASE/TARGET)에 등록된 코드 근거의 파일 경로여야 합니다."
                }
            }
        }
    }

    fun findIntent(repositoryKey: String, revision: String, path: String, line: Int): LineIntents {
        val normalizedRepositoryKey = GitHubRepository.parse(repositoryKey).key
        val normalizedRevision = requireFullRevision(revision)
        val normalizedPath = requireRepositoryRelativePath(path)
        require(line > 0) { "코드 줄 번호는 1 이상이어야 합니다." }

        val found = repository.findPublishedByAnchor(normalizedRepositoryKey, normalizedRevision, normalizedPath, line, LINE_INTENT_LIMIT + 1)
        return LineIntents(found.take(LINE_INTENT_LIMIT), found.size > LINE_INTENT_LIMIT)
    }

    private fun validateCreate(command: CreateChangeRecordCommand) {
        require(command.requestId.isNotBlank()) { "요청 ID는 비어 있을 수 없습니다." }
        require(redactor.redact(command.requestId) == command.requestId) {
            "요청 ID에는 비밀값이나 개인 절대 경로를 넣을 수 없습니다."
        }
        require(command.title.isNotBlank()) { "제목은 비어 있을 수 없습니다." }
        require(command.requestSummary.isNotBlank()) { "요청 요약은 비어 있을 수 없습니다." }
        require(SHA_256.matches(command.snapshotDigest)) { "기록에는 SHA-256 스냅샷 해시가 필요합니다." }
        require(command.decisions.isNotEmpty()) { "구현 결정을 1개 이상 입력하세요." }
        require(command.codeAnchors.isNotEmpty()) { "관련 코드를 1개 이상 입력하세요." }
        require(command.baseRevision != null || command.codeAnchors.none { it.side == CodeSide.BASE }) {
            "변경 전 관련 코드에는 변경 전 커밋 해시(전체 길이)가 필요합니다."
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
        creationDigest: String,
    ): ChangeRecord {
        // 저장된 저장소 키는 소문자로 정규화돼 있다.
        if (existing.repositoryKey != repositoryKey || existing.createdBy.subject != actor.subject || existing.creationDigest != creationDigest) {
            throw ChangeRecordRequestConflictException()
        }
        return existing
    }

    private fun redact(decision: Decision): Decision = decision.copy(
        summary = redact(decision.summary, 1000, "구현 결정 요약"),
        rationale = decision.rationale?.let { redact(it, 2000, "결정 이유") },
    )

    private fun normalize(anchor: CodeAnchor): CodeAnchor = anchor.copy(
        relativePath = requireRepositoryRelativePath(anchor.relativePath),
        symbolName = anchor.symbolName?.let { redact(it, 500, "코드 심볼 이름") },
        contentHash = anchor.contentHash.lowercase(),
        relatedPath = anchor.relatedPath?.let(::requireRepositoryRelativePath),
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
}
