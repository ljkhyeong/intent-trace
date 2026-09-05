package io.intenttrace.record.adapter.out.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaseRevisionMigrationTest {
    @Test
    fun `미사용 기준 revision 열을 제거하고 기존 기록을 보존한다`() {
        val databaseName = "base-revision-migration-${UUID.randomUUID()}"
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("classpath:db/migration")
            .target("5")
            .load()
            .migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                insert into change_records (
                    id, request_id, repository_key, base_revision, snapshot_digest, title, request_summary,
                    status, created_by, created_by_subject, created_at, version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, UUID.randomUUID().toString())
                statement.setString(2, "base-revision-migration")
                statement.setString(3, "acme/intent-trace")
                statement.setString(4, "a".repeat(40))
                statement.setString(5, "b".repeat(64))
                statement.setString(6, "기존 기록")
                statement.setString(7, "미사용 열 제거 뒤에도 보존한다.")
                statement.setString(8, "DRAFT")
                statement.setString(9, "lim")
                statement.setString(10, "github:1")
                statement.setObject(11, OffsetDateTime.parse("2026-08-29T00:00:00Z"))
                statement.setLong(12, 0)
                statement.executeUpdate()
            }
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("6").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from change_records").use { result ->
                    result.next()
                    assertEquals(1, result.getInt(1))
                }
            }
            connection.metaData.getColumns(null, null, "change_records", "base_revision").use { columns ->
                assertFalse(columns.next())
            }
        }
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select title, base_revision from change_records").use { result ->
                    assertTrue(result.next())
                    assertEquals("기존 기록", result.getString("title"))
                    assertNull(result.getString("base_revision"))
                    assertFalse(result.next())
                }
                statement.executeQuery("select count(*) from record_activities").use { result ->
                    result.next()
                    assertEquals(0, result.getInt(1))
                }
            }
        }
    }
}
