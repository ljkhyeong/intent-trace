package io.intenttrace.record.application

import io.intenttrace.record.domain.ChangeRecord
import java.util.UUID

interface ChangeRecordRepository {
    fun findById(id: UUID): ChangeRecord?

    fun findByRequestId(requestId: String): ChangeRecord?

    fun findPublished(repositoryKey: String, targetRevision: String): List<ChangeRecord>

    fun saveNew(record: ChangeRecord): ChangeRecord

    fun update(record: ChangeRecord, expectedVersion: Long): ChangeRecord
}
