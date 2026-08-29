package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals

@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "INTENT_TRACE_POSTGRES_SMOKE", matches = "true")
class PostgresRepositorySmokeTest(
    @Autowired private val facade: ChangeRecordFacade,
) {
    @Test
    fun `PostgreSQL에서 migration과 변경 기록 조회를 확인한다`() {
        val draft = facade.create(
            CreateChangeRecordCommand(
                requestId = "postgres-smoke",
                repositoryKey = "Acme/Intent-Trace",
                baseRevision = null,
                snapshotDigest = digest,
                title = "PostgreSQL 검증",
                requestSummary = "실제 PostgreSQL에서 기록 수명주기를 확인한다.",
                decisions = listOf(Decision("PostgreSQL 17을 사용한다.", null, PurposeSource.STATED_BY_USER)),
                codeAnchors = listOf(CodeAnchor("src/App.kt", "App", 3, 7, "c".repeat(64))),
                verifications = emptyList(),
                openQuestions = emptyList(),
            ),
            actor,
        )
        val confirmed = facade.confirm(
            ConfirmChangeRecordCommand(draft.id, draft.version, revision, digest),
            actor,
        )
        val published = facade.publish(
            PublishChangeRecordCommand(confirmed.id, confirmed.version, digest),
            actor,
        )

        val found = facade.findIntent("ACME/INTENT-TRACE", revision, "src/App.kt", 5)

        assertEquals("acme/intent-trace", published.repositoryKey)
        assertEquals(listOf(published.id), found.map { it.id })
    }

    companion object {
        private val actor = ActorIdentity.github(1, "postgres-smoke")
        private val digest = "a".repeat(64)
        private val revision = "b".repeat(40)
    }
}
