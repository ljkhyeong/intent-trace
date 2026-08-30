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
        val (_, actor) = ownedContributor(command.recordId)
        val replacement = facade.get(command.replacementRecordId)
        requireOwner(replacement, actor)
        return facade.supersede(command, actor)
    }

    fun findIntent(repositoryKey: String, revision: String, path: String, line: Int): List<ChangeRecord> {
        access.requireReader(repositoryKey)
        return facade.findIntent(repositoryKey, revision, path, line)
    }

    fun requireOwnedContributor(recordId: UUID): ChangeRecord = ownedContributor(recordId).record

    private fun ownedContributor(recordId: UUID): OwnedContributorRecord {
        val record = facade.get(recordId)
        val actor = access.requireContributor(record.repositoryKey)
        requireOwner(record, actor)
        return OwnedContributorRecord(record, actor)
    }

    private fun requireOwner(record: ChangeRecord, actor: ActorIdentity) {
        if (actor.subject != record.createdBy.subject) {
            throw ChangeRecordOwnershipException()
        }
    }

    private fun ChangeRecord.isTeamVisible(): Boolean =
        status == ChangeRecordStatus.PUBLISHED || status == ChangeRecordStatus.SUPERSEDED

    private data class OwnedContributorRecord(
        val record: ChangeRecord,
        val actor: ActorIdentity,
    )
}
