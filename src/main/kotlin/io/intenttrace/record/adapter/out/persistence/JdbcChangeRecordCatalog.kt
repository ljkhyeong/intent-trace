package io.intenttrace.record.adapter.out.persistence

import io.intenttrace.record.application.ChangeRecordCatalog
import io.intenttrace.record.application.ChangeRecordSummary
import io.intenttrace.record.application.RecordCatalogQuery
import io.intenttrace.record.application.toSummary
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.ZoneOffset

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
            // 게시 결과는 항상 같은 PR의 게시 시도를 기록한 뒤 저장하므로 시도만 확인한다.
            conditions += """
                exists (select 1 from github_publication_attempts a where a.change_record_id = r.id
                    and a.repository_key = :repositoryKey and a.pull_number = :pullNumber)
            """.trimIndent()
            parameters["pullNumber"] = number
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
            select r.* from change_records r where ${conditions.joinToString(" and ")}
            order by r.created_at desc, r.id desc limit :limit
            """.trimIndent(),
            parameters,
        ) { row, index -> changeRecordRowMapper.mapRow(row, index).toSummary() }
    }
}
