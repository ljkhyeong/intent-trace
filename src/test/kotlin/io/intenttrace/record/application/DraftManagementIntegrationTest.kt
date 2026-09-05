package io.intenttrace.record.application

import io.intenttrace.IntentTraceApplication
import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserAccessGateway
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [IntentTraceApplication::class, DraftManagementIntegrationTest.Configuration::class],
    properties = ["spring.datasource.url=jdbc:h2:mem:draft-management;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"],
)
class DraftManagementIntegrationTest(
    @Autowired private val records: TeamChangeRecordService,
    @Autowired private val catalog: ChangeRecordCatalogService,
    @Autowired private val session: TestSession,
) {
    @Test
    fun `초안 수정과 확인 취소는 최초 요청의 멱등성을 보존하고 공개 본문은 잠근다`() {
        session.actor = owner
        val command = command("edit-${UUID.randomUUID()}")
        val draft = records.create(command)
        val confirmed = records.confirm(ConfirmChangeRecordCommand(draft.id, draft.version, revision, digest))
        assertFailsWith<IllegalStateException> { records.revise(draft.id, confirmed.version, command.copy(title = "수정")) }
        val reopened = records.reopen(draft.id, confirmed.version)
        assertNull(reopened.targetRevision)
        assertNull(reopened.confirmedAt)
        val updated = records.revise(draft.id, reopened.version, command.copy(title = "확인 후 수정", openQuestions = listOf("추가 점검")))
        assertEquals("확인 후 수정", records.get(draft.id).title)
        assertEquals(listOf("추가 점검"), records.get(draft.id).openQuestions)
        assertEquals(updated.id, records.create(command).id)
        assertFailsWith<ChangeRecordRequestConflictException> { records.create(command.copy(title = "다른 생성 요청")) }
        assertFailsWith<ConcurrentChangeRecordUpdateException> { records.revise(draft.id, reopened.version, command) }
        val rechecked = records.confirm(ConfirmChangeRecordCommand(draft.id, updated.version, revision, digest))
        val published = records.publish(PublishChangeRecordCommand(draft.id, rechecked.version, digest))
        assertFailsWith<IllegalStateException> { records.revise(draft.id, published.version, command) }
        assertFailsWith<IllegalStateException> { records.discard(draft.id, published.version) }
    }

    @Test
    fun `내 초안 목록은 다른 작성자와 폐기 기록을 제외하고 페이지를 이어간다`() {
        session.actor = owner
        val first = records.create(command("first-${UUID.randomUUID()}", "acme/catalog"))
        val second = records.create(command("second-${UUID.randomUUID()}", "acme/catalog"))
        session.actor = other
        records.create(command("other-${UUID.randomUUID()}", "acme/catalog"))
        assertFailsWith<ChangeRecordOwnershipException> { records.discard(first.id, first.version) }
        assertFailsWith<ChangeRecordOwnershipException> { records.revise(first.id, first.version, command("first", "acme/catalog")) }
        session.actor = owner
        val page = catalog.list("acme/catalog", RecordScope.MINE, limit = 1)
        val next = catalog.list("acme/catalog", RecordScope.MINE, cursor = assertNotNull(page.nextCursor), limit = 1)
        assertEquals(setOf(first.id, second.id), (page.items + next.items).map { it.id }.toSet())
        assertNull(next.nextCursor)
        val discarded = records.discard(first.id, first.version)
        assertEquals(ChangeRecordStatus.DISCARDED, discarded.status)
        assertEquals(listOf(second.id), catalog.list("acme/catalog", RecordScope.MINE).items.map { it.id })
        assertFailsWith<IllegalStateException> { records.confirm(ConfirmChangeRecordCommand(first.id, discarded.version, revision, digest)) }
        assertTrue(catalog.list("acme/catalog", RecordScope.TEAM).items.isEmpty())
        assertFailsWith<IllegalArgumentException> { catalog.list("acme/catalog", status = ChangeRecordStatus.DRAFT) }
    }

    @Test
    fun `검색은 제목 요청 판단 근거를 찾고 특수문자와 비공개 범위를 보존한다`() {
        session.actor = owner
        val repository = "acme/keyword"
        val seeds = listOf(
            command("title-${UUID.randomUUID()}", repository).copy(title = "키워드 Cache 변경"),
            command("request-${UUID.randomUUID()}", repository).copy(requestSummary = "키워드 Cache 요청"),
            command("decision-${UUID.randomUUID()}", repository).copy(decisions = listOf(Decision("키워드 Cache 판단", null, PurposeSource.STATED_BY_USER))),
            command("rationale-${UUID.randomUUID()}", repository).copy(decisions = listOf(Decision("판단", "키워드 Cache 근거 100%_정확!", PurposeSource.STATED_BY_USER))),
        ).map { records.create(it) }
        session.actor = other
        records.create(command("private-${UUID.randomUUID()}", repository).copy(title = "키워드 Cache 비공개"))
        session.actor = owner
        val first = catalog.list(repository, RecordScope.MINE, q = "  cache  ", limit = 2)
        val second = catalog.list(repository, RecordScope.MINE, q = "cache", limit = 2, cursor = assertNotNull(first.nextCursor))
        assertEquals(seeds.map { it.id }.toSet(), (first.items + second.items).map { it.id }.toSet())
        assertNull(second.nextCursor)
        assertEquals(listOf(seeds.last().id), catalog.list(repository, RecordScope.MINE, q = "%_정확!").items.map { it.id })
        assertTrue(catalog.list(repository, q = "cache").items.isEmpty())
        assertTrue(catalog.list(repository, RecordScope.MINE, q = "없는 검색어").items.isEmpty())
        assertFailsWith<IllegalArgumentException> { catalog.list(repository, q = "a".repeat(201)) }
    }

    class TestSession(var actor: ActorIdentity = owner) : CurrentGitHubUserSession {
        override fun require(): GitHubUserSession = GitHubUserSession(actor, "test-token")
    }

    @TestConfiguration
    class Configuration {
        @Bean @Primary fun currentSession() = TestSession()
        @Bean @Primary fun accessGateway() = object : GitHubUserAccessGateway {
            override fun authenticate(accessToken: String) = owner
            override fun repositoryRole(accessToken: String, repository: GitHubRepository) = RepositoryRole.CONTRIBUTOR
        }
    }

    companion object {
        private val owner = ActorIdentity.github(1, "owner")
        private val other = ActorIdentity.github(2, "other")
        private val digest = "a".repeat(64)
        private val revision = "b".repeat(40)
        private fun command(id: String, repo: String = "acme/drafts") = CreateChangeRecordCommand(
            id, repo, null, digest, "초안 수정", "작성자 피드백을 반영한다.",
            listOf(Decision("공개 본문은 보존한다.", null, PurposeSource.STATED_BY_USER)),
            listOf(CodeAnchor("src/App.kt", null, 1, 2, "c".repeat(64))), emptyList(), emptyList(),
        )
    }
}
