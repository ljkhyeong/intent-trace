package io.intenttrace.record.adapter.out.persistence

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.ChangeRecordSummary
import io.intenttrace.record.domain.ChangeRecordStatus
import org.springframework.jdbc.core.RowMapper
import java.time.OffsetDateTime
import java.util.UUID

internal val changeRecordSummaryRowMapper = RowMapper<ChangeRecordSummary> { row, _ ->
    ChangeRecordSummary(
        id = UUID.fromString(row.getString("id")),
        repositoryKey = row.getString("repository_key"),
        title = row.getString("title"),
        requestSummary = row.getString("request_summary"),
        version = row.getLong("version"),
        status = ChangeRecordStatus.valueOf(row.getString("status")),
        targetRevision = row.getString("target_revision"),
        createdBy = ActorIdentity(row.getString("created_by_subject"), row.getString("created_by_login")),
        createdAt = row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        publishedAt = row.getObject("published_at", OffsetDateTime::class.java)?.toInstant(),
        supersededBy = row.getString("superseded_by")?.let(UUID::fromString),
    )
}
