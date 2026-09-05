package io.intenttrace.record.application

import io.intenttrace.record.domain.ChangeRecord
import java.util.UUID

interface ChangeRecordRepository {
    fun findById(id: UUID): ChangeRecord?

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
