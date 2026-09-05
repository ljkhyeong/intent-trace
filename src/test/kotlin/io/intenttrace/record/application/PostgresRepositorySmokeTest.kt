package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.VerificationRun
import io.intenttrace.record.domain.VerificationSource
import io.intenttrace.publication.application.GitHubPublicationTracking
import io.intenttrace.publication.application.PublicationOperation
import io.intenttrace.publication.application.PublicationAttemptStatus
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.publication.domain.GitHubPublication
import java.time.Instant
import java.util.UUID
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
    @Autowired private val tracking: GitHubPublicationTracking,
) {
    @Test
    fun `PostgreSQL에서 migration과 변경 기록 조회를 확인한다`() {
        val draft = facade.create(
            CreateChangeRecordCommand(
                requestId = "postgres-smoke",
                repositoryKey = "Acme/Intent-Trace",
                baseRevision = "e".repeat(40),
                snapshotDigest = digest,
                title = "PostgreSQL 검증",
                requestSummary = "실제 PostgreSQL에서 기록 수명주기를 확인한다.",
                decisions = listOf(Decision("PostgreSQL 17을 사용한다.", null, PurposeSource.STATED_BY_USER)),
                codeAnchors = listOf(CodeAnchor("src/App.kt", "App", 3, 7, "c".repeat(64)),
                    CodeAnchor("src/Old.kt", null, 1, 2, "d".repeat(64), CodeSide.BASE)),
                verifications = listOf(VerificationRun("test", 0, Instant.EPOCH, Instant.EPOCH, digest,
                    "f".repeat(64), "수집 결과", VerificationSource.LOCAL_RUNNER_REPORTED)),
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
        assertEquals(CodeSide.BASE, found.single().codeAnchors.last().side)
        assertEquals(VerificationSource.LOCAL_RUNNER_REPORTED, found.single().verifications.single().source)
        val target = GitHubPullRequestTarget("acme", "intent-trace", 1)
        val attempt = tracking.start(published.id, target, PublicationOperation.PUBLISH)
        val publication = GitHubPublication(UUID.randomUUID(), published.id, target, revision, 42,
            "https://github.test/check/42", digest, Instant.EPOCH)
        tracking.finish(attempt, PublicationAttemptStatus.SUCCEEDED, null, publication)
        val saved = tracking.recent(published.id, target).single()
        assertEquals(PublicationAttemptStatus.SUCCEEDED, saved.status)
        assertEquals(42L, saved.checkRunId)
        assertEquals(digest, saved.contentDigest)
    }

    companion object {
        private val actor = ActorIdentity.github(1, "postgres-smoke")
        private val digest = "a".repeat(64)
        private val revision = "b".repeat(40)
    }
}
