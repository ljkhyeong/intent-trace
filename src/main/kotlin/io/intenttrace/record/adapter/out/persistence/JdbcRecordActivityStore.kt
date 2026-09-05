package io.intenttrace.record.adapter.out.persistence

import io.intenttrace.record.application.RecordActivity
import io.intenttrace.record.application.RecordActivityStore
import io.intenttrace.record.application.RecordOperation
import io.intenttrace.record.domain.ChangeRecordStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcRecordActivityStore(private val jdbc: JdbcTemplate) : RecordActivityStore {
    // 기록 갱신이 실패하면 이력도 함께 취소해야 한다.
    @Transactional(propagation = Propagation.MANDATORY)
    override fun append(activity: RecordActivity) {
        jdbc.update("""insert into record_activities
            (record_id, operation, actor_subject, previous_version, version, previous_status, status, occurred_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)""",
            activity.recordId.toString(), activity.operation.name, activity.actorSubject, activity.previousVersion,
            activity.version, activity.previousStatus?.name, activity.status.name,
            OffsetDateTime.ofInstant(activity.occurredAt, ZoneOffset.UTC))
    }

    override fun list(recordId: UUID, authorView: Boolean, beforeVersion: Long?, limit: Int): List<RecordActivity> {
        val parameters = mutableListOf<Any>(recordId.toString())
        val sql = buildString {
            append("select * from record_activities where record_id = ?")
            if (!authorView) append(" and operation in ('PUBLISH', 'SUPERSEDE')")
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

    override fun hasCreation(recordId: UUID): Boolean = jdbc.queryForObject(
        "select count(*) from record_activities where record_id = ? and operation = 'CREATE'", Long::class.java, recordId.toString(),
    ) == 1L
}
