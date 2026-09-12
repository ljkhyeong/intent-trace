package io.intenttrace.record.adapter.out.persistence

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.record.application.ChangeRecordCatalog
import io.intenttrace.record.application.ChangeRecordSummary
import io.intenttrace.record.application.RecordCatalogQuery
import io.intenttrace.record.domain.ChangeRecordStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcChangeRecordCatalog(private val jdbc: NamedParameterJdbcTemplate) : ChangeRecordCatalog {
    override fun search(query: RecordCatalogQuery): List<ChangeRecordSummary> {
        val parameters = mutableMapOf<String, Any>(
            "repositoryKey" to query.repositoryKey, "statuses" to query.statuses.map { it.name }, "limit" to query.limit,
        )
        val conditions = mutableListOf("r.repository_key = :repositoryKey", "r.status in (:statuses)")
        query.authorSubject?.let { conditions += "r.created_by_subject = :authorSubject"; parameters["authorSubject"] = it }
        query.path?.let {
            conditions += "exists (select 1 from code_anchors a where a.record_id = r.id and a.relative_path = :path)"
            parameters["path"] = it
        }
        query.pullNumber?.let { number ->
            conditions += """
                (exists (select 1 from github_publication_attempts a where a.change_record_id = r.id
                    and a.repository_key = :repositoryKey and a.pull_number = :pullNumber)
                 or exists (select 1 from github_publications p where p.change_record_id = r.id
                    and p.repository_owner = :owner and p.repository_name = :repository and p.pull_number = :pullNumber))
            """.trimIndent()
            val repository = GitHubRepository.parse(query.repositoryKey)
            parameters["pullNumber"] = number
            parameters["owner"] = repository.canonicalOwner
            parameters["repository"] = repository.canonicalName
        }
        query.keyword?.let {
            val pattern = "%${it.replace("!", "!!").replace("%", "!%").replace("_", "!_")}%"
            conditions += """
                (lower(r.title) like lower(:keyword) escape '!' or lower(r.request_summary) like lower(:keyword) escape '!'
                 or exists (select 1 from change_decisions d where d.record_id = r.id
                     and (lower(d.summary) like lower(:keyword) escape '!' or lower(d.rationale) like lower(:keyword) escape '!')))
            """.trimIndent()
            parameters["keyword"] = pattern
        }
        query.cursor?.let {
            conditions += "(r.created_at < :createdAt or (r.created_at = :createdAt and r.id < :cursorId))"
            parameters["createdAt"] = it.createdAt.atOffset(ZoneOffset.UTC)
            parameters["cursorId"] = it.id.toString()
        }
        return jdbc.query(
            """
            select r.id, r.title, r.request_summary, r.repository_key, r.target_revision, r.status,
                   r.created_by_subject, r.created_by, r.created_at, r.superseded_by, r.version, r.published_at
            from change_records r where ${conditions.joinToString(" and ")}
            order by r.created_at desc, r.id desc limit :limit
            """.trimIndent(),
            parameters,
            changeRecordSummaryRowMapper,
        )
    }
}

internal val changeRecordSummaryRowMapper = RowMapper<ChangeRecordSummary> { row, _ ->
    ChangeRecordSummary(
        id = UUID.fromString(row.getString("id")),
        repositoryKey = row.getString("repository_key"),
        title = row.getString("title"),
        requestSummary = row.getString("request_summary"),
        version = row.getLong("version"),
        status = ChangeRecordStatus.valueOf(row.getString("status")),
        targetRevision = row.getString("target_revision"),
        createdBy = ActorIdentity(row.getString("created_by_subject"), row.getString("created_by")),
        createdAt = row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        publishedAt = row.getObject("published_at", OffsetDateTime::class.java)?.toInstant(),
        supersededBy = row.getString("superseded_by")?.let(UUID::fromString),
    )
}
