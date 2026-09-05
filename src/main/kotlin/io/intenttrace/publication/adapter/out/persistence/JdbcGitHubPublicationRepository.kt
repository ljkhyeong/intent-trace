package io.intenttrace.publication.adapter.out.persistence

import io.intenttrace.publication.application.GitHubPublicationRepository
import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.identity.domain.GitHubRepository
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcGitHubPublicationRepository(
    private val jdbcTemplate: JdbcTemplate,
) : GitHubPublicationRepository {
    @Transactional(readOnly = true)
    override fun find(changeRecordId: UUID, target: GitHubPullRequestTarget): GitHubPublication? {
        val repository = GitHubRepository(target.owner, target.repository)
        return jdbcTemplate.query(
            """
            select *
            from github_publications
            where change_record_id = ?
              and repository_owner = ?
              and repository_name = ?
              and pull_number = ?
            """.trimIndent(),
            { resultSet, _ -> mapPublication(resultSet) },
            changeRecordId.toString(),
            repository.canonicalOwner,
            repository.canonicalName,
            target.pullNumber,
        ).firstOrNull()
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun save(publication: GitHubPublication): GitHubPublication {
        val repository = GitHubRepository(publication.target.owner, publication.target.repository)
        val updated = update(publication)
        if (updated == 1) {
            return requireNotNull(find(publication.changeRecordId, publication.target))
        }

        try {
            jdbcTemplate.update(
                """
                insert into github_publications (
                    id, change_record_id, repository_owner, repository_name, pull_number,
                    head_revision, check_run_id, check_run_url, content_digest, published_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                publication.id.toString(),
                publication.changeRecordId.toString(),
                repository.canonicalOwner,
                repository.canonicalName,
                publication.target.pullNumber,
                publication.headRevision,
                publication.checkRunId,
                publication.checkRunUrl,
                publication.contentDigest,
                publication.publishedAt.toDatabaseTime(),
            )
        } catch (_: DuplicateKeyException) {
            if (update(publication) != 1) {
                throw IllegalStateException("GitHub 게시 이력을 저장하지 못했습니다.")
            }
        }
        return requireNotNull(find(publication.changeRecordId, publication.target))
    }

    private fun update(publication: GitHubPublication): Int {
        val repository = GitHubRepository(publication.target.owner, publication.target.repository)
        return jdbcTemplate.update(
            """
            update github_publications
            set head_revision = ?, check_run_id = ?, check_run_url = ?,
                content_digest = ?, published_at = ?
            where change_record_id = ?
              and repository_owner = ?
              and repository_name = ?
              and pull_number = ?
            """.trimIndent(),
            publication.headRevision,
            publication.checkRunId,
            publication.checkRunUrl,
            publication.contentDigest,
            publication.publishedAt.toDatabaseTime(),
            publication.changeRecordId.toString(),
            repository.canonicalOwner,
            repository.canonicalName,
            publication.target.pullNumber,
        )
    }

    private fun mapPublication(resultSet: ResultSet): GitHubPublication = GitHubPublication(
        id = UUID.fromString(resultSet.getString("id")),
        changeRecordId = UUID.fromString(resultSet.getString("change_record_id")),
        target = GitHubPullRequestTarget(
            owner = resultSet.getString("repository_owner"),
            repository = resultSet.getString("repository_name"),
            pullNumber = resultSet.getInt("pull_number"),
        ),
        headRevision = resultSet.getString("head_revision"),
        checkRunId = resultSet.getLong("check_run_id"),
        checkRunUrl = resultSet.getString("check_run_url"),
        contentDigest = resultSet.getString("content_digest"),
        publishedAt = resultSet.getObject("published_at", OffsetDateTime::class.java).toInstant(),
    )

    private fun Instant.toDatabaseTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
