package io.intenttrace.record.adapter.out.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails

class RepositoryKeyMigrationTest {
    @Test
    fun `기존 저장소 키와 게시 대상을 소문자로 바꾸고 대문자 저장을 막는다`() {
        val databaseName = "repository-key-migration-${UUID.randomUUID()}"
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("classpath:db/migration")
            .target("3")
            .load()
            .migrate()

        val recordId = UUID.randomUUID().toString()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                insert into change_records (
                    id, request_id, repository_key, snapshot_digest, title, request_summary,
                    status, created_by, created_by_subject, created_at, version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, recordId)
                statement.setString(2, "repository-key-migration")
                statement.setString(3, "Acme/Intent-Trace")
                statement.setString(4, "a".repeat(64))
                statement.setString(5, "저장소 키 migration")
                statement.setString(6, "기존 대소문자를 정리한다.")
                statement.setString(7, "PUBLISHED")
                statement.setString(8, "lim")
                statement.setString(9, "github:1")
                statement.setObject(10, OffsetDateTime.parse("2026-08-29T00:00:00Z"))
                statement.setLong(11, 0)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                insert into github_publications (
                    id, change_record_id, repository_owner, repository_name, pull_number,
                    head_revision, check_run_id, check_run_url, content_digest, published_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, UUID.randomUUID().toString())
                statement.setString(2, recordId)
                statement.setString(3, "Acme")
                statement.setString(4, "Intent-Trace")
                statement.setInt(5, 12)
                statement.setString(6, "b".repeat(40))
                statement.setLong(7, 42)
                statement.setString(8, "https://github.test/check-runs/42")
                statement.setString(9, "c".repeat(64))
                statement.setObject(10, OffsetDateTime.parse("2026-08-29T00:01:00Z"))
                statement.executeUpdate()
            }
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select repository_key from change_records").use { result ->
                    result.next()
                    assertEquals("acme/intent-trace", result.getString("repository_key"))
                }
                statement.executeQuery("select repository_owner, repository_name from github_publications").use { result ->
                    result.next()
                    assertEquals("acme", result.getString("repository_owner"))
                    assertEquals("intent-trace", result.getString("repository_name"))
                }
                assertFails {
                    statement.executeUpdate("update change_records set repository_key = 'Acme/Intent-Trace'")
                }
            }
        }
    }
}
