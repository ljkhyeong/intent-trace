package io.intenttrace.record.adapter.out.persistence

import io.intenttrace.record.application.RecordActivity
import io.intenttrace.record.application.ActivityVisibility
import io.intenttrace.record.application.RecordActivityStore
import io.intenttrace.record.application.RecordOperation
import io.intenttrace.record.domain.ChangeRecordStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JdbcRecordActivityStore(private val jdbc: JdbcTemplate) : RecordActivityStore {
    override fun list(recordId: UUID, visibility: ActivityVisibility, beforeVersion: Long?, limit: Int): List<RecordActivity> {
        val parameters = mutableListOf<Any>(recordId.toString())
        val sql = buildString {
            append("select * from record_activities where record_id = ?")
            if (visibility == ActivityVisibility.TEAM) append(" and operation in ('PUBLISH', 'SUPERSEDE')")
            if (beforeVersion != null) { append(" and version < ?"); parameters.add(beforeVersion) }
            append(" order by version desc limit ?"); parameters.add(limit)
        }
        return jdbc.query(sql, { row, _ -> RecordActivity(
            UUID.fromString(row.getString("record_id")), RecordOperation.valueOf(row.getString("operation")),
            row.getString("actor_subject"), row.getObject("previous_version", Long::class.javaObjectType),
            row.getLong("version"), row.getString("previous_status")?.let(ChangeRecordStatus::valueOf),
            ChangeRecordStatus.valueOf(row.getString("status")), row.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
        ) }, *parameters.toTypedArray())
    }
}
