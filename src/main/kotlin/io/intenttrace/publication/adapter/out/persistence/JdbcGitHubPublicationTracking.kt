package io.intenttrace.publication.adapter.out.persistence

import io.intenttrace.publication.application.GitHubPublicationTracking
import io.intenttrace.publication.application.PublicationAttempt
import io.intenttrace.publication.application.PublicationAttemptStatus
import io.intenttrace.publication.application.PublicationOperation
import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JdbcGitHubPublicationTracking(
    private val jdbc: JdbcTemplate,
    private val namedJdbc: NamedParameterJdbcTemplate,
    private val clock: Clock,
) : GitHubPublicationTracking {
    @EventListener(ApplicationReadyEvent::class)
    fun markInterruptedAttempts() {
        jdbc.update("update github_publication_attempts set status = 'RESULT_UNKNOWN', failure_code = 'APP_RESTARTED', finished_at = ? where status = 'IN_PROGRESS'", OffsetDateTime.now(clock))
    }

    override fun start(recordId: UUID, target: GitHubPullRequestTarget, operation: PublicationOperation): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "insert into github_publication_attempts (id, change_record_id, repository_key, pull_number, operation, status, started_at) values (?, ?, ?, ?, ?, 'IN_PROGRESS', ?)",
            id.toString(), recordId.toString(), target.repositoryKey, target.pullNumber, operation.name, OffsetDateTime.now(clock),
        )
        return id
    }

    override fun finish(attemptId: UUID, status: PublicationAttemptStatus, failureCode: String?, publication: GitHubPublication?) {
        jdbc.update(
            "update github_publication_attempts set status = ?, failure_code = ?, check_run_id = ?, content_digest = ?, finished_at = ? where id = ? and status = 'IN_PROGRESS'",
            status.name, failureCode, publication?.checkRunId, publication?.contentDigest, OffsetDateTime.now(clock), attemptId.toString(),
        )
    }

    override fun recent(recordId: UUID, target: GitHubPullRequestTarget): List<PublicationAttempt> = jdbc.query(
        "select * from github_publication_attempts where change_record_id = ? and repository_key = ? and pull_number = ? order by started_at desc, id desc limit 20",
        { row, _ -> mapAttempt(row) }, recordId.toString(), target.repositoryKey, target.pullNumber,
    )

    override fun latest(recordIds: Collection<UUID>, target: GitHubPullRequestTarget): Map<UUID, PublicationAttempt> {
        if (recordIds.isEmpty()) return emptyMap()
        return namedJdbc.query(
            """
            select * from (
                select a.*, row_number() over (
                    partition by change_record_id order by started_at desc, id desc
                ) as attempt_rank
                from github_publication_attempts a
                where change_record_id in (:recordIds) and repository_key = :repositoryKey and pull_number = :pullNumber
            ) ranked where attempt_rank = 1
            """.trimIndent(),
            mapOf("recordIds" to recordIds.map(UUID::toString), "repositoryKey" to target.repositoryKey, "pullNumber" to target.pullNumber),
            { row, _ -> UUID.fromString(row.getString("change_record_id")) to mapAttempt(row) },
        ).toMap()
    }

    private fun mapAttempt(row: ResultSet): PublicationAttempt = PublicationAttempt(
        UUID.fromString(row.getString("id")), PublicationOperation.valueOf(row.getString("operation")),
        PublicationAttemptStatus.valueOf(row.getString("status")), row.getString("failure_code"),
        row.getObject("check_run_id", Long::class.javaObjectType), row.getString("content_digest"),
        row.getObject("started_at", OffsetDateTime::class.java).toInstant(), row.getObject("finished_at", OffsetDateTime::class.java)?.toInstant(),
    )
}
