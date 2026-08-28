package io.intenttrace.record.adapter.out.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

class GitHubActorIdentityMigrationTest {
    @Test
    fun `기존 작성자 이름을 legacy subject로 보존한다`() {
        val databaseName = "migration-${UUID.randomUUID()}"
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        val versionTwo = Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("classpath:db/migration")
            .target("2")
            .load()
        versionTwo.migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                insert into change_records (
                    id, request_id, repository_key, snapshot_digest, title, request_summary,
                    status, created_by, created_at, version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, UUID.randomUUID().toString())
                statement.setString(2, "legacy-request")
                statement.setString(3, "acme/intent-trace")
                statement.setString(4, "a".repeat(64))
                statement.setString(5, "기존 기록")
                statement.setString(6, "마이그레이션 대상")
                statement.setString(7, "DRAFT")
                statement.setString(8, "Lim")
                statement.setObject(9, OffsetDateTime.parse("2026-08-28T00:00:00Z"))
                statement.setLong(10, 0)
                statement.executeUpdate()
            }
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select created_by_subject from change_records").use { result ->
                    result.next()
                    assertEquals("legacy:lim", result.getString("created_by_subject"))
                }
            }
        }
    }
}
