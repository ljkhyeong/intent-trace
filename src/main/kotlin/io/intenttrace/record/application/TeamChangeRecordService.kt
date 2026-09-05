package io.intenttrace.record.application

import io.intenttrace.identity.application.RepositoryAccessService
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TeamChangeRecordService(
    private val facade: ChangeRecordFacade,
    private val access: RepositoryAccessService,
) {
    fun create(command: CreateChangeRecordCommand): ChangeRecord {
        val actor = access.requireContributor(command.repositoryKey)
        return facade.create(command, actor)
    }

    fun createSuccessor(recordId: UUID, command: SuccessorDraftCommand): ChangeRecord {
        val (source, actor) = ownedContributor(recordId)
        source.requireSuccessorSource(actor)
        return facade.create(CreateChangeRecordCommand(
            requestId = command.requestId, repositoryKey = source.repositoryKey,
            baseRevision = command.baseRevision, snapshotDigest = command.snapshotDigest,
            title = source.title, requestSummary = source.requestSummary,
            decisions = source.decisions, codeAnchors = command.codeAnchors,
            verifications = emptyList(), openQuestions = source.openQuestions, derivedFromRecordId = source.id,
        ), actor)
    }

    fun get(recordId: UUID): ChangeRecord {
        val record = facade.get(recordId)
        val actor = access.requireReader(record.repositoryKey)
        if (!record.isTeamVisible() && actor.subject != record.createdBy.subject) {
            throw ChangeRecordOwnershipException()
        }
        return record
    }

    fun confirm(command: ConfirmChangeRecordCommand): ChangeRecord {
        val (record, actor) = ownedContributor(command.recordId)
        return facade.confirm(record, command, actor)
    }

    fun publish(command: PublishChangeRecordCommand): ChangeRecord {
        val (record, actor) = ownedContributor(command.recordId)
        return facade.publish(record, command, actor)
    }

    fun supersede(command: SupersedeChangeRecordCommand): ChangeRecord {
        val (record, actor) = ownedContributor(command.recordId)
        val replacement = ownedContributor(command.replacementRecordId).record
        return facade.supersede(record, replacement, command, actor)
    }

    fun revise(recordId: UUID, expectedVersion: Long, command: CreateChangeRecordCommand): ChangeRecord {
        val (record, actor) = ownedContributor(recordId)
        return facade.revise(record, expectedVersion, command, actor)
    }

    fun reopen(recordId: UUID, expectedVersion: Long): ChangeRecord {
        val (record, actor) = ownedContributor(recordId)
        return facade.reopen(record, expectedVersion, actor)
    }

    fun discard(recordId: UUID, expectedVersion: Long): ChangeRecord {
        val (record, actor) = ownedContributor(recordId)
        return facade.discard(record, expectedVersion, actor)
    }

    fun findIntent(repositoryKey: String, revision: String, path: String, line: Int): List<ChangeRecord> {
        access.requireReader(repositoryKey)
        return facade.findIntent(repositoryKey, revision, path, line)
    }

    fun requireOwnedContributor(recordId: UUID): ChangeRecord = ownedContributor(recordId).record

    private fun ownedContributor(recordId: UUID): OwnedContributorRecord {
        val record = facade.get(recordId)
        val actor = access.requireContributor(record.repositoryKey)
        if (actor.subject != record.createdBy.subject) {
            throw ChangeRecordOwnershipException()
        }
        return OwnedContributorRecord(record, actor)
    }

    private fun ChangeRecord.isTeamVisible(): Boolean =
        status == ChangeRecordStatus.PUBLISHED || status == ChangeRecordStatus.SUPERSEDED

    private data class OwnedContributorRecord(
        val record: ChangeRecord,
        val actor: ActorIdentity,
    )
}
