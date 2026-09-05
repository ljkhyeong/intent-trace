package io.intenttrace.record.adapter.out.persistence

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.ChangeRecordCatalog
import io.intenttrace.record.application.ChangeRecordSummary
import io.intenttrace.record.application.RecordCatalogQuery
import io.intenttrace.record.domain.ChangeRecordStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcChangeRecordCatalog(private val jdbc: JdbcTemplate) : ChangeRecordCatalog {
    override fun search(query: RecordCatalogQuery): List<ChangeRecordSummary> {
        val parameters = mutableListOf<Any>(query.repositoryKey)
        val conditions = mutableListOf("r.repository_key = ?")
        conditions += "r.status in (${query.statuses.joinToString { "?" }})"
        parameters.addAll(query.statuses.map { it.name })
        query.authorSubject?.let { conditions += "r.created_by_subject = ?"; parameters += it }
        query.path?.let {
            conditions += "exists (select 1 from code_anchors a where a.record_id = r.id and a.relative_path = ?)"
            parameters += it
        }
        query.keyword?.let {
            val pattern = "%${it.replace("!", "!!").replace("%", "!%").replace("_", "!_")}%"
            conditions += """
                (lower(r.title) like lower(?) escape '!' or lower(r.request_summary) like lower(?) escape '!'
                 or exists (select 1 from change_decisions d where d.record_id = r.id
                     and (lower(d.summary) like lower(?) escape '!' or lower(d.rationale) like lower(?) escape '!')))
            """.trimIndent()
            repeat(4) { parameters += pattern }
        }
        query.cursor?.let {
            conditions += "(r.created_at < ? or (r.created_at = ? and r.id < ?))"
            val timestamp = OffsetDateTime.ofInstant(it.createdAt, ZoneOffset.UTC)
            parameters.addAll(listOf(timestamp, timestamp, it.id.toString()))
        }
        parameters += query.limit
        return jdbc.query(
            """
            select r.id, r.title, r.request_summary, r.repository_key, r.target_revision, r.status,
                   r.created_by_subject, r.created_by, r.created_at, r.superseded_by, r.version
            from change_records r where ${conditions.joinToString(" and ")}
            order by r.created_at desc, r.id desc limit ?
            """.trimIndent(),
            { row, _ ->
                ChangeRecordSummary(
                    UUID.fromString(row.getString("id")), row.getString("title"), row.getString("request_summary"),
                    row.getString("repository_key"), row.getString("target_revision"),
                    ChangeRecordStatus.valueOf(row.getString("status")),
                    ActorIdentity(row.getString("created_by_subject"), row.getString("created_by")),
                    row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    row.getString("superseded_by")?.let(UUID::fromString), row.getLong("version"),
                )
            },
            *parameters.toTypedArray(),
        )
    }
}
