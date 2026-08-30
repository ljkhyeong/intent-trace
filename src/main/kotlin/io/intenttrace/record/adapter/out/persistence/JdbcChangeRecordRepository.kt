package io.intenttrace.record.adapter.out.persistence

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.ChangeRecordRepository
import io.intenttrace.record.application.ConcurrentChangeRecordUpdateException
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import io.intenttrace.record.domain.VerificationRun
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcChangeRecordRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val namedJdbcTemplate: NamedParameterJdbcTemplate,
) : ChangeRecordRepository {
    private val recordRowMapper = RowMapper<ChangeRecord> { resultSet, _ -> mapRecord(resultSet) }

    @Transactional(readOnly = true)
    override fun findById(id: UUID): ChangeRecord? =
        jdbcTemplate.query(
            "select * from change_records where id = ?",
            recordRowMapper,
            id.toString(),
        ).firstOrNull()?.let(::hydrate)

    @Transactional(readOnly = true)
    override fun findByRequestId(requestId: String): ChangeRecord? =
        jdbcTemplate.query(
            "select * from change_records where request_id = ?",
            recordRowMapper,
            requestId,
        ).firstOrNull()?.let(::hydrate)

    @Transactional(readOnly = true)
    override fun findPublishedByAnchor(
        repositoryKey: String,
        targetRevision: String,
        relativePath: String,
        line: Int,
    ): List<ChangeRecord> {
        val records = jdbcTemplate.query(
            """
            select records.*
            from change_records records
            where records.repository_key = ?
              and records.target_revision = ?
              and records.status in ('PUBLISHED', 'SUPERSEDED')
              and exists (
                  select 1
                  from code_anchors anchors
                  where anchors.record_id = records.id
                    and anchors.relative_path = ?
                    and anchors.start_line <= ?
                    and anchors.end_line >= ?
              )
            order by records.published_at desc
            """.trimIndent(),
            recordRowMapper,
            repositoryKey,
            targetRevision,
            relativePath,
            line,
            line,
        )
        return hydrate(records)
    }

    @Transactional
    override fun saveNew(record: ChangeRecord): ChangeRecord {
        jdbcTemplate.update(
            """
            insert into change_records (
                id, request_id, repository_key, target_revision,
                snapshot_digest, title, request_summary, status, created_by, created_by_subject,
                created_at, confirmed_at, published_at, superseded_by, version
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            record.id.toString(),
            record.requestId,
            record.repositoryKey,
            record.targetRevision,
            record.snapshotDigest,
            record.title,
            record.requestSummary,
            record.status.name,
            record.createdBy.login,
            record.createdBy.subject,
            record.createdAt.toDatabaseTime(),
            record.confirmedAt?.toDatabaseTime(),
            record.publishedAt?.toDatabaseTime(),
            record.supersededBy?.toString(),
            record.version,
        )
        insertDecisions(record)
        insertCodeAnchors(record)
        insertVerifications(record)
        insertOpenQuestions(record)
        return record
    }

    @Transactional
    override fun update(record: ChangeRecord, expectedVersion: Long): ChangeRecord {
        val updated = jdbcTemplate.update(
            """
            update change_records
            set target_revision = ?, status = ?, confirmed_at = ?, published_at = ?,
                superseded_by = ?, version = ?
            where id = ? and version = ?
            """.trimIndent(),
            record.targetRevision,
            record.status.name,
            record.confirmedAt?.toDatabaseTime(),
            record.publishedAt?.toDatabaseTime(),
            record.supersededBy?.toString(),
            record.version,
            record.id.toString(),
            expectedVersion,
        )
        if (updated != 1) {
            throw ConcurrentChangeRecordUpdateException(record.id)
        }
        return record
    }

    private fun hydrate(record: ChangeRecord): ChangeRecord = hydrate(listOf(record)).single()

    private fun hydrate(records: List<ChangeRecord>): List<ChangeRecord> {
        if (records.isEmpty()) return emptyList()
        val recordIds = records.map(ChangeRecord::id)
        val decisions = findChildren(recordIds, "summary, rationale, source", "change_decisions") { resultSet ->
            Decision(
                summary = resultSet.getString("summary"),
                rationale = resultSet.getString("rationale"),
                source = PurposeSource.valueOf(resultSet.getString("source")),
            )
        }
        val anchors = findChildren(
            recordIds,
            "relative_path, symbol_name, start_line, end_line, content_hash",
            "code_anchors",
        ) { resultSet ->
            CodeAnchor(
                relativePath = resultSet.getString("relative_path"),
                symbolName = resultSet.getString("symbol_name"),
                startLine = resultSet.getInt("start_line"),
                endLine = resultSet.getInt("end_line"),
                contentHash = resultSet.getString("content_hash"),
            )
        }
        val verifications = findChildren(
            recordIds,
            "command_text, exit_code, started_at, finished_at, snapshot_digest, output_digest, summary",
            "verification_runs",
        ) { resultSet ->
            VerificationRun(
                command = resultSet.getString("command_text"),
                exitCode = resultSet.getInt("exit_code"),
                startedAt = resultSet.getObject("started_at", OffsetDateTime::class.java).toInstant(),
                finishedAt = resultSet.getObject("finished_at", OffsetDateTime::class.java).toInstant(),
                snapshotDigest = resultSet.getString("snapshot_digest"),
                outputDigest = resultSet.getString("output_digest"),
                summary = resultSet.getString("summary"),
            )
        }
        val questions = findChildren(recordIds, "description", "open_questions") { resultSet ->
            resultSet.getString("description")
        }

        return records.map { record ->
            record.copy(
                decisions = decisions[record.id].orEmpty(),
                codeAnchors = anchors[record.id].orEmpty(),
                verifications = verifications[record.id].orEmpty(),
                openQuestions = questions[record.id].orEmpty(),
            )
        }
    }

    private fun insertDecisions(record: ChangeRecord) {
        val batch = record.decisions.mapIndexed { index, decision ->
            arrayOf<Any?>(
                UUID.randomUUID().toString(),
                record.id.toString(),
                index,
                decision.summary,
                decision.rationale,
                decision.source.name,
            )
        }
        executeBatch(
            """
            insert into change_decisions (
                id, record_id, sequence_number, summary, rationale, source
            ) values (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            batch,
        )
    }

    private fun insertCodeAnchors(record: ChangeRecord) {
        val batch = record.codeAnchors.mapIndexed { index, anchor ->
            arrayOf<Any?>(
                UUID.randomUUID().toString(),
                record.id.toString(),
                index,
                anchor.relativePath,
                anchor.symbolName,
                anchor.startLine,
                anchor.endLine,
                anchor.contentHash,
            )
        }
        executeBatch(
            """
            insert into code_anchors (
                id, record_id, sequence_number, relative_path, symbol_name,
                start_line, end_line, content_hash
            ) values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            batch,
        )
    }

    private fun insertVerifications(record: ChangeRecord) {
        val batch = record.verifications.mapIndexed { index, verification ->
            arrayOf<Any?>(
                UUID.randomUUID().toString(),
                record.id.toString(),
                index,
                verification.command,
                verification.exitCode,
                verification.startedAt.toDatabaseTime(),
                verification.finishedAt.toDatabaseTime(),
                verification.snapshotDigest,
                verification.outputDigest,
                verification.summary,
            )
        }
        executeBatch(
            """
            insert into verification_runs (
                id, record_id, sequence_number, command_text, exit_code,
                started_at, finished_at, snapshot_digest, output_digest, summary
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            batch,
        )
    }

    private fun insertOpenQuestions(record: ChangeRecord) {
        val batch = record.openQuestions.mapIndexed { index, question ->
            arrayOf<Any?>(
                UUID.randomUUID().toString(),
                record.id.toString(),
                index,
                question,
            )
        }
        executeBatch(
            """
            insert into open_questions (id, record_id, sequence_number, description)
            values (?, ?, ?, ?)
            """.trimIndent(),
            batch,
        )
    }

    private fun executeBatch(sql: String, batch: List<Array<Any?>>) {
        if (batch.isNotEmpty()) jdbcTemplate.batchUpdate(sql, batch)
    }

    private fun <T> findChildren(
        recordIds: List<UUID>,
        columns: String,
        table: String,
        mapper: (ResultSet) -> T,
    ): Map<UUID, List<T>> {
        return namedJdbcTemplate.query(
            "select record_id, $columns from $table where record_id in (:recordIds) order by record_id, sequence_number",
            mapOf("recordIds" to recordIds.map(UUID::toString)),
            { resultSet, _ -> UUID.fromString(resultSet.getString("record_id")) to mapper(resultSet) },
        ).groupBy({ it.first }, { it.second })
    }

    private fun mapRecord(resultSet: ResultSet): ChangeRecord = ChangeRecord(
        id = UUID.fromString(resultSet.getString("id")),
        requestId = resultSet.getString("request_id"),
        repositoryKey = resultSet.getString("repository_key"),
        targetRevision = resultSet.getString("target_revision"),
        snapshotDigest = resultSet.getString("snapshot_digest"),
        title = resultSet.getString("title"),
        requestSummary = resultSet.getString("request_summary"),
        status = ChangeRecordStatus.valueOf(resultSet.getString("status")),
        createdBy = ActorIdentity(
            subject = resultSet.getString("created_by_subject"),
            login = resultSet.getString("created_by"),
        ),
        createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        confirmedAt = resultSet.getNullableInstant("confirmed_at"),
        publishedAt = resultSet.getNullableInstant("published_at"),
        supersededBy = resultSet.getString("superseded_by")?.let(UUID::fromString),
        version = resultSet.getLong("version"),
        decisions = emptyList(),
        codeAnchors = emptyList(),
        verifications = emptyList(),
        openQuestions = emptyList(),
    )

    private fun Instant.toDatabaseTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun ResultSet.getNullableInstant(column: String): Instant? =
        getObject(column, OffsetDateTime::class.java)?.toInstant()
}
