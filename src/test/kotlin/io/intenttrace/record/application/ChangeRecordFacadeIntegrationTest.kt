package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.publication.application.GitHubPublicationRepository
import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

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
            repositoryKey = "acme/intent-trace",
            baseRevision = null,
            snapshotDigest = digest,
            title = "변경 의도 저장",
            requestSummary = "API_KEY=secret-value 요청을 안전하게 요약한다.",
            decisions = listOf(Decision("작성자 확인 후 공개한다.", null, PurposeSource.STATED_BY_USER)),
            codeAnchors = listOf(CodeAnchor("src/App.kt", "App", 10, 20, "d".repeat(64))),
            verifications = emptyList(),
            openQuestions = listOf("GitHub 게시 자동화는 아직 검증하지 않았다."),
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
        val found = facade.findIntent("acme/intent-trace", revision, "src/App.kt", 15)

        assertEquals(first.id, retried.id)
        assertEquals(actor, first.createdBy)
        assertEquals("API_KEY=[REDACTED] 요청을 안전하게 요약한다.", first.requestSummary)
        assertEquals(ChangeRecordStatus.PUBLISHED, published.status)
        assertEquals(listOf(published.id), found.map { it.id })
    }

    @Test
    fun `같은 기록과 PR의 GitHub 게시 이력을 갱신한다`() {
        val draft = facade.create(
            CreateChangeRecordCommand(
                requestId = "integration-github-publication",
                repositoryKey = "acme/intent-trace",
                baseRevision = null,
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
