package io.intenttrace.record.adapter.out.persistence

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.ChangeRecordRepository
import io.intenttrace.record.application.ConcurrentChangeRecordUpdateException
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.VerificationSource
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import io.intenttrace.record.domain.VerificationRun
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
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
    ): List<ChangeRecord> =
        jdbcTemplate.query(
            """
            select records.*
            from change_records records
            where records.repository_key = ?
              and records.status in ('PUBLISHED', 'SUPERSEDED')
              and exists (
                  select 1
                  from code_anchors anchors
                  where anchors.record_id = records.id
                    and ((anchors.anchor_side = 'TARGET' and records.target_revision = ?)
                      or (anchors.anchor_side = 'BASE' and records.base_revision = ?))
                    and anchors.relative_path = ?
                    and anchors.start_line <= ?
                    and anchors.end_line >= ?
              )
            order by records.published_at desc
            """.trimIndent(),
            recordRowMapper,
            repositoryKey,
            targetRevision,
            targetRevision,
            relativePath,
            line,
            line,
        ).map(::hydrate)

    @Transactional
    override fun saveNew(record: ChangeRecord): ChangeRecord {
        jdbcTemplate.update(
            """
            insert into change_records (
                id, request_id, repository_key, base_revision, target_revision,
                snapshot_digest, title, request_summary, status, created_by, created_by_subject,
                created_at, confirmed_at, published_at, superseded_by, version, creation_digest
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            record.id.toString(),
            record.requestId,
            record.repositoryKey,
            record.baseRevision,
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
            record.creationDigest,
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
                superseded_by = ?, version = ?, creation_digest = ?
            where id = ? and version = ?
            """.trimIndent(),
            record.targetRevision,
            record.status.name,
            record.confirmedAt?.toDatabaseTime(),
            record.publishedAt?.toDatabaseTime(),
            record.supersededBy?.toString(),
            record.version,
            record.creationDigest,
            record.id.toString(),
            expectedVersion,
        )
        if (updated != 1) {
            throw ConcurrentChangeRecordUpdateException(record.id)
        }
        if (record.status == ChangeRecordStatus.DRAFT) {
            jdbcTemplate.update(
                "update change_records set base_revision = ?, snapshot_digest = ?, title = ?, request_summary = ? where id = ?",
                record.baseRevision, record.snapshotDigest, record.title, record.requestSummary, record.id.toString(),
            )
            listOf("change_decisions", "code_anchors", "verification_runs", "open_questions").forEach { table ->
                jdbcTemplate.update("delete from $table where record_id = ?", record.id.toString())
            }
            insertDecisions(record)
            insertCodeAnchors(record)
            insertVerifications(record)
            insertOpenQuestions(record)
        }
        return record
    }

    private fun hydrate(record: ChangeRecord): ChangeRecord = record.copy(
        decisions = findDecisions(record.id),
        codeAnchors = findCodeAnchors(record.id),
        verifications = findVerifications(record.id),
        openQuestions = findOpenQuestions(record.id),
    )

    private fun insertDecisions(record: ChangeRecord) {
        record.decisions.forEachIndexed { index, decision ->
            jdbcTemplate.update(
                """
                insert into change_decisions (
                    id, record_id, sequence_number, summary, rationale, source
                ) values (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID().toString(),
                record.id.toString(),
                index,
                decision.summary,
                decision.rationale,
                decision.source.name,
            )
        }
    }

    private fun insertCodeAnchors(record: ChangeRecord) {
        record.codeAnchors.forEachIndexed { index, anchor ->
            jdbcTemplate.update(
                """
                insert into code_anchors (
                    id, record_id, sequence_number, relative_path, symbol_name,
                    start_line, end_line, content_hash, anchor_side, related_path
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID().toString(),
                record.id.toString(),
                index,
                anchor.relativePath,
                anchor.symbolName,
                anchor.startLine,
                anchor.endLine,
                anchor.contentHash,
                anchor.side.name,
                anchor.relatedPath,
            )
        }
    }

    private fun insertVerifications(record: ChangeRecord) {
        record.verifications.forEachIndexed { index, verification ->
            jdbcTemplate.update(
                """
                insert into verification_runs (
                    id, record_id, sequence_number, command_text, exit_code,
                    started_at, finished_at, snapshot_digest, output_digest, summary, source
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
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
                verification.source.name,
            )
        }
    }

    private fun insertOpenQuestions(record: ChangeRecord) {
        record.openQuestions.forEachIndexed { index, question ->
            jdbcTemplate.update(
                """
                insert into open_questions (id, record_id, sequence_number, description)
                values (?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID().toString(),
                record.id.toString(),
                index,
                question,
            )
        }
    }

    private fun findDecisions(recordId: UUID): List<Decision> = jdbcTemplate.query(
        "select * from change_decisions where record_id = ? order by sequence_number",
        { resultSet, _ ->
            Decision(
                summary = resultSet.getString("summary"),
                rationale = resultSet.getString("rationale"),
                source = PurposeSource.valueOf(resultSet.getString("source")),
            )
        },
        recordId.toString(),
    )

    private fun findCodeAnchors(recordId: UUID): List<CodeAnchor> = jdbcTemplate.query(
        "select * from code_anchors where record_id = ? order by sequence_number",
        { resultSet, _ ->
            CodeAnchor(
                relativePath = resultSet.getString("relative_path"),
                symbolName = resultSet.getString("symbol_name"),
                startLine = resultSet.getInt("start_line"),
                endLine = resultSet.getInt("end_line"),
                contentHash = resultSet.getString("content_hash"),
                side = CodeSide.valueOf(resultSet.getString("anchor_side")),
                relatedPath = resultSet.getString("related_path"),
            )
        },
        recordId.toString(),
    )

    private fun findVerifications(recordId: UUID): List<VerificationRun> = jdbcTemplate.query(
        "select * from verification_runs where record_id = ? order by sequence_number",
        { resultSet, _ ->
            VerificationRun(
                command = resultSet.getString("command_text"),
                exitCode = resultSet.getInt("exit_code"),
                startedAt = resultSet.getObject("started_at", OffsetDateTime::class.java).toInstant(),
                finishedAt = resultSet.getObject("finished_at", OffsetDateTime::class.java).toInstant(),
                snapshotDigest = resultSet.getString("snapshot_digest"),
                outputDigest = resultSet.getString("output_digest"),
                summary = resultSet.getString("summary"),
                source = VerificationSource.valueOf(resultSet.getString("source")),
            )
        },
        recordId.toString(),
    )

    private fun findOpenQuestions(recordId: UUID): List<String> = jdbcTemplate.query(
        "select description from open_questions where record_id = ? order by sequence_number",
        { resultSet, _ -> resultSet.getString("description") },
        recordId.toString(),
    )

    private fun mapRecord(resultSet: ResultSet): ChangeRecord = ChangeRecord(
        id = UUID.fromString(resultSet.getString("id")),
        requestId = resultSet.getString("request_id"),
        repositoryKey = resultSet.getString("repository_key"),
        baseRevision = resultSet.getString("base_revision"),
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
        creationDigest = resultSet.getString("creation_digest"),
        decisions = emptyList(),
        codeAnchors = emptyList(),
        verifications = emptyList(),
        openQuestions = emptyList(),
    )

    private fun Instant.toDatabaseTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun ResultSet.getNullableInstant(column: String): Instant? =
        getObject(column, OffsetDateTime::class.java)?.toInstant()
}
