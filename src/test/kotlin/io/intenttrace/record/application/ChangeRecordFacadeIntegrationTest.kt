package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.publication.application.GitHubPublicationRepository
import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.adapter.`in`.web.ChangeRecordResponse
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import io.intenttrace.record.domain.VerificationRun
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:intent-trace-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.h2.console.enabled=false",
    ],
)
class ChangeRecordFacadeIntegrationTest(
    @Autowired private val facade: ChangeRecordFacade,
    @Autowired private val gitHubPublicationRepository: GitHubPublicationRepository,
) {
    @Test
    fun `같은 요청은 한 번만 만들고 공개 기록을 코드 줄로 찾는다`() {
        val command = CreateChangeRecordCommand(
            requestId = "integration-turn-1",
            repositoryKey = "Acme/Intent-Trace",
            snapshotDigest = digest,
            title = "변경 의도 저장",
            requestSummary = "API_KEY=secret-value 요청을 안전하게 요약한다.",
            decisions = listOf(
                Decision("작성자 확인 후 공개한다.", "팀 공개 전에 작성자가 확인해야 한다.", PurposeSource.STATED_BY_USER),
                Decision("검증 결과를 함께 저장한다.", null, PurposeSource.CONFIRMED_AI_SUMMARY),
            ),
            codeAnchors = listOf(
                CodeAnchor("./src//App.kt/", "App ghu_testOnlyToken /Users/example/project/App.kt", 10, 20, "d".repeat(64)),
                CodeAnchor("src/Extra.kt", "Extra", 1, 3, "e".repeat(64)),
            ),
            verifications = listOf(
                VerificationRun(
                    command = "./gradlew test",
                    exitCode = 0,
                    startedAt = Instant.parse("2026-08-29T00:00:00Z"),
                    finishedAt = Instant.parse("2026-08-29T00:01:00Z"),
                    snapshotDigest = digest,
                    outputDigest = "f".repeat(64),
                    summary = "전체 테스트 통과",
                ),
                VerificationRun(
                    command = "scripts/verify-postgres.sh",
                    exitCode = 0,
                    startedAt = Instant.parse("2026-08-29T00:02:00Z"),
                    finishedAt = Instant.parse("2026-08-29T00:03:00Z"),
                    snapshotDigest = "c".repeat(64),
                    outputDigest = "1".repeat(64),
                    summary = "이전 스냅샷 PostgreSQL 검증",
                ),
            ),
            openQuestions = listOf(
                "GitHub 게시 자동화는 아직 검증하지 않았다.",
                "IDE 연동은 후속 범위다.",
            ),
        )

        val first = facade.create(command, actor)
        val retried = facade.create(command, actor)
        val confirmed = facade.confirm(
            ConfirmChangeRecordCommand(first.id, first.version, revision, digest),
            actor,
        )
        val published = facade.publish(
            PublishChangeRecordCommand(confirmed.id, confirmed.version, digest),
            actor,
        )
        val unrelatedDraft = facade.create(
            command.copy(
                requestId = "integration-turn-unrelated",
                codeAnchors = listOf(CodeAnchor("src/Other.kt", "Other", 10, 20, "e".repeat(64))),
            ),
            actor,
        )
        val unrelatedConfirmed = facade.confirm(
            ConfirmChangeRecordCommand(unrelatedDraft.id, unrelatedDraft.version, revision, digest),
            actor,
        )
        facade.publish(
            PublishChangeRecordCommand(unrelatedConfirmed.id, unrelatedConfirmed.version, digest),
            actor,
        )
        val relatedDraft = facade.create(
            command.copy(
                requestId = "integration-turn-related",
                decisions = listOf(Decision("같은 줄의 다른 판단", null, PurposeSource.STATED_BY_USER)),
                codeAnchors = listOf(CodeAnchor("src/App.kt", "App", 15, 16, "e".repeat(64))),
                verifications = emptyList(),
                openQuestions = listOf("다른 기록의 질문"),
            ),
            actor,
        )
        val relatedConfirmed = facade.confirm(
            ConfirmChangeRecordCommand(relatedDraft.id, relatedDraft.version, revision, digest),
            actor,
        )
        val relatedPublished = facade.publish(
            PublishChangeRecordCommand(relatedConfirmed.id, relatedConfirmed.version, digest),
            actor,
        )
        val found = facade.findIntent("ACME/INTENT-TRACE", revision, "src/./App.kt", 15)

        assertEquals(first.id, retried.id)
        assertEquals("acme/intent-trace", first.repositoryKey)
        assertEquals(actor, first.createdBy)
        assertEquals("API_KEY=[REDACTED] 요청을 안전하게 요약한다.", first.requestSummary)
        assertEquals("src/App.kt", first.codeAnchors.first().relativePath)
        assertEquals("App [REDACTED] [REDACTED]", first.codeAnchors.first().symbolName)
        assertEquals(ChangeRecordStatus.PUBLISHED, published.status)
        assertEquals(listOf(relatedPublished.id, published.id), found.map { it.id })
        for (expected in listOf(published, relatedPublished)) {
            val hydrated = found.single { it.id == expected.id }
            assertEquals(expected.decisions, hydrated.decisions)
            assertEquals(expected.codeAnchors, hydrated.codeAnchors)
            assertEquals(expected.verifications, hydrated.verifications)
            assertEquals(expected.openQuestions, hydrated.openQuestions)
        }
        val hydrated = found.single { it.id == published.id }
        assertEquals(listOf(true, false), ChangeRecordResponse.from(hydrated).verifications.map { it.current })
    }

    @Test
    fun `같은 version으로 두 번 갱신하면 두 번째 저장을 거부한다`() {
        val draft = facade.create(
            CreateChangeRecordCommand(
                requestId = "integration-optimistic-lock",
                repositoryKey = "acme/intent-trace",
                snapshotDigest = digest,
                title = "낙관적 잠금",
                requestSummary = "같은 version의 중복 갱신을 막는다.",
                decisions = listOf(Decision("version 조건을 사용한다.", null, PurposeSource.STATED_BY_USER)),
                codeAnchors = listOf(CodeAnchor("src/Lock.kt", "Lock", 1, 2, "d".repeat(64))),
                verifications = emptyList(),
                openQuestions = emptyList(),
            ),
            actor,
        )
        val command = ConfirmChangeRecordCommand(draft.id, draft.version, revision, digest)
        facade.confirm(draft, command, actor)

        assertFailsWith<ConcurrentChangeRecordUpdateException> {
            facade.confirm(draft, command, actor)
        }
    }

    @Test
    fun `같은 기록과 PR의 GitHub 게시 이력을 갱신한다`() {
        val draft = facade.create(
            CreateChangeRecordCommand(
                requestId = "integration-github-publication",
                repositoryKey = "acme/intent-trace",
                snapshotDigest = digest,
                title = "GitHub 게시 이력",
                requestSummary = "같은 PR 게시를 갱신한다.",
                decisions = listOf(Decision("Check Run을 재사용한다.", null, PurposeSource.STATED_BY_USER)),
                codeAnchors = listOf(CodeAnchor("src/App.kt", "App", 1, 2, "d".repeat(64))),
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
        val target = GitHubPullRequestTarget("acme", "intent-trace", 12)
        val first = GitHubPublication(
            id = UUID.randomUUID(),
            changeRecordId = published.id,
            target = target,
            headRevision = revision,
            checkRunId = 42,
            checkRunUrl = "https://github.test/check-runs/42",
            contentDigest = "e".repeat(64),
            publishedAt = Instant.parse("2026-08-27T15:00:00Z"),
        )

        gitHubPublicationRepository.save(first)
        val updated = gitHubPublicationRepository.save(
            first.copy(
                checkRunId = 43,
                checkRunUrl = "https://github.test/check-runs/43",
                publishedAt = Instant.parse("2026-08-27T15:01:00Z"),
            ),
        )

        assertEquals(first.id, updated.id)
        assertEquals(43L, updated.checkRunId)
        assertEquals(updated, gitHubPublicationRepository.find(published.id, target))
    }

    companion object {
        private val digest = "a".repeat(64)
        private val revision = "b".repeat(40)
        private val actor = ActorIdentity.github(1, "lim")
    }
}
