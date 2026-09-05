package io.intenttrace.record.adapter.out.persistence

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.ChangeRecordRepository
import io.intenttrace.record.application.RecordActivity
import io.intenttrace.record.application.RecordActivityStore
import io.intenttrace.record.application.RecordOperation
import io.intenttrace.record.application.ChangeRecordSummary
import io.intenttrace.record.application.ConcurrentChangeRecordUpdateException
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.VerificationSource
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import io.intenttrace.record.domain.VerificationRun
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.SliceImpl
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcChangeRecordRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val activities: RecordActivityStore,
    private val namedJdbcTemplate: NamedParameterJdbcTemplate,
) : ChangeRecordRepository {
    private val recordRowMapper = RowMapper<ChangeRecord> { resultSet, _ -> mapRecord(resultSet) }

    override fun findSummaries(
        repositoryKey: String,
        statuses: Set<ChangeRecordStatus>,
        authorSubject: String?,
        relativePath: String?,
        pageable: Pageable,
    ): Slice<ChangeRecordSummary> {
        val rows = namedJdbcTemplate.query(
            """
            select records.id, records.repository_key, records.title, records.request_summary, records.version, records.status,
                   records.target_revision, records.created_by, records.created_by_subject,
                   records.created_at, records.published_at, records.superseded_by
            from change_records records
            where records.repository_key = :repositoryKey and records.status in (:statuses)
            ${if (authorSubject != null) "and records.created_by_subject = :authorSubject" else ""}
            ${if (relativePath != null) """
            and exists (
                select 1 from code_anchors anchors
                where anchors.record_id = records.id and anchors.relative_path = :relativePath
            )
            """ else ""}
            order by records.created_at desc, records.id desc
            limit :limit offset :offset
            """.trimIndent(),
            mapOf(
                "repositoryKey" to repositoryKey,
                "statuses" to statuses.map { it.name },
                "authorSubject" to authorSubject,
                "relativePath" to relativePath,
                "limit" to pageable.pageSize + 1,
                "offset" to pageable.offset,
            ),
        ) { row, _ ->
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
        return SliceImpl(rows.take(pageable.pageSize), pageable, rows.size > pageable.pageSize)
    }

    @Transactional(readOnly = true)
    override fun findById(id: UUID): ChangeRecord? =
        jdbcTemplate.query(
            "select * from change_records where id = ?",
            recordRowMapper,
            id.toString(),
        ).firstOrNull()?.let(::hydrate)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun findByIdsForUpdate(ids: Set<UUID>): List<ChangeRecord> = hydrate(
        namedJdbcTemplate.query(
            "select * from change_records where id in (:ids) order by id for update",
            mapOf("ids" to ids.map(UUID::toString)),
            recordRowMapper,
        ),
    )

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
        )
        return hydrate(records)
    }

    @Transactional
    override fun saveNew(record: ChangeRecord): ChangeRecord {
        jdbcTemplate.update(
            """
            insert into change_records (
                id, request_id, repository_key, base_revision, target_revision,
                snapshot_digest, title, request_summary, status, created_by, created_by_subject,
                created_at, confirmed_at, published_at, superseded_by, version, creation_digest, derived_from_record_id
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            record.derivedFromRecordId?.toString(),
        )
        insertDecisions(record)
        insertCodeAnchors(record)
        insertVerifications(record)
        insertOpenQuestions(record)
        activities.append(RecordActivity(record.id, RecordOperation.CREATE, record.createdBy.subject,
            null, record.version, null, record.status, record.createdAt))
        return record
    }

    @Transactional
    override fun update(record: ChangeRecord, expectedVersion: Long, activity: RecordActivity): ChangeRecord {
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
        activities.append(activity)
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
            "relative_path, symbol_name, start_line, end_line, content_hash, anchor_side, related_path",
            "code_anchors",
        ) { resultSet ->
            CodeAnchor(
                relativePath = resultSet.getString("relative_path"),
                symbolName = resultSet.getString("symbol_name"),
                startLine = resultSet.getInt("start_line"),
                endLine = resultSet.getInt("end_line"),
                contentHash = resultSet.getString("content_hash"),
                side = CodeSide.valueOf(resultSet.getString("anchor_side")),
                relatedPath = resultSet.getString("related_path"),
            )
        }
        val verifications = findChildren(
            recordIds,
            "command_text, exit_code, started_at, finished_at, snapshot_digest, output_digest, summary, source",
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
                source = VerificationSource.valueOf(resultSet.getString("source")),
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
        jdbcTemplate.batchUpdate(
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
                anchor.side.name,
                anchor.relatedPath,
            )
        }
        jdbcTemplate.batchUpdate(
            """
            insert into code_anchors (
                id, record_id, sequence_number, relative_path, symbol_name,
                start_line, end_line, content_hash, anchor_side, related_path
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                verification.source.name,
            )
        }
        jdbcTemplate.batchUpdate(
            """
            insert into verification_runs (
                id, record_id, sequence_number, command_text, exit_code,
                started_at, finished_at, snapshot_digest, output_digest, summary, source
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        jdbcTemplate.batchUpdate(
            """
            insert into open_questions (id, record_id, sequence_number, description)
            values (?, ?, ?, ?)
            """.trimIndent(),
            batch,
        )
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
        derivedFromRecordId = resultSet.getString("derived_from_record_id")?.let(UUID::fromString),
        decisions = emptyList(),
        codeAnchors = emptyList(),
        verifications = emptyList(),
        openQuestions = emptyList(),
    )

    private fun Instant.toDatabaseTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun ResultSet.getNullableInstant(column: String): Instant? =
        getObject(column, OffsetDateTime::class.java)?.toInstant()
}
