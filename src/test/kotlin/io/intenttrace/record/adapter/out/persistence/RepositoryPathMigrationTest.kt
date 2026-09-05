package io.intenttrace.record.adapter.out.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails

class RepositoryPathMigrationTest {
    @Test
    fun `기존 코드 경로를 정규화하고 비정규 경로 저장을 막는다`() {
        val databaseName = "path-migration-${UUID.randomUUID()}"
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        val versionFour = Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("classpath:db/migration")
            .target("4")
            .load()
        versionFour.migrate()

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
                statement.setString(2, "path-migration")
                statement.setString(3, "acme/intent-trace")
                statement.setString(4, "a".repeat(64))
                statement.setString(5, "경로 migration")
                statement.setString(6, "기존 경로를 정규화한다.")
                statement.setString(7, "DRAFT")
                statement.setString(8, "lim")
                statement.setString(9, "github:1")
                statement.setObject(10, OffsetDateTime.parse("2026-08-29T00:00:00Z"))
                statement.setLong(11, 0)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                insert into code_anchors (
                    id, record_id, sequence_number, relative_path, start_line, end_line, content_hash
                ) values (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, UUID.randomUUID().toString())
                statement.setString(2, recordId)
                statement.setInt(3, 0)
                statement.setString(4, "./src//main/./App.kt/")
                statement.setInt(5, 1)
                statement.setInt(6, 2)
                statement.setString(7, "b".repeat(64))
                statement.executeUpdate()
            }
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select relative_path from code_anchors").use { result ->
                    result.next()
                    assertEquals("src/main/App.kt", result.getString("relative_path"))
                }
                assertFails {
                    statement.executeUpdate("update code_anchors set relative_path = 'src//App.kt'")
                }
            }
        }
    }
}
