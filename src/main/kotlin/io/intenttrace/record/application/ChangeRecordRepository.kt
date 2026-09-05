package io.intenttrace.record.application

import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import java.util.UUID

interface ChangeRecordRepository {
    fun findSummaries(
        repositoryKey: String,
        statuses: Set<ChangeRecordStatus>,
        authorSubject: String?,
        relativePath: String?,
        pageable: Pageable,
    ): Slice<ChangeRecordSummary>

    fun findById(id: UUID): ChangeRecord?

    fun findByIdsForUpdate(ids: Set<UUID>): List<ChangeRecord>

    fun findByRequestId(requestId: String): ChangeRecord?

    fun findPublishedByAnchor(
        repositoryKey: String,
        targetRevision: String,
        relativePath: String,
        line: Int,
    ): List<ChangeRecord>

    fun saveNew(record: ChangeRecord): ChangeRecord

    fun update(record: ChangeRecord, expectedVersion: Long, activity: RecordActivity): ChangeRecord
}
