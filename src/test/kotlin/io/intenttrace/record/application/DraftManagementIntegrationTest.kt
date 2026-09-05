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
    @Autowired private val activities: RecordActivityService,
    @Autowired private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
) {
    @Test
    fun `비교는 출처 변경과 실제 순서 변경을 구분하고 중복 항목을 임의로 합치지 않는다`() {
        session.actor = owner
        val record = records.create(command("comparison-${UUID.randomUUID()}"))
        val first = Decision("첫 판단", "첫 근거", PurposeSource.STATED_BY_USER)
        val second = Decision("다음 판단", null, PurposeSource.UNKNOWN)
        val sourceChanged = first.copy(source = PurposeSource.CONFIRMED_AI_SUMMARY)
        val original = record.copy(decisions = listOf(first, second))
        val inserted = comparisonDetails(original, original.copy(decisions = listOf(Decision("추가 판단", null, PurposeSource.INFERRED), sourceChanged, second)))
        assertEquals(setOf(ItemChange.ADDED, ItemChange.MODIFIED), inserted.map { it.change }.toSet())
        assertEquals(listOf("source"), inserted.single { it.change == ItemChange.MODIFIED }.changedProperties)
        assertTrue(inserted.none { it.moved })
        val reordered = comparisonDetails(original, original.copy(decisions = listOf(second, sourceChanged)))
        assertTrue(reordered.all { it.moved })
        assertEquals(setOf(ItemChange.MODIFIED, ItemChange.MOVED), reordered.map { it.change }.toSet())
        val duplicates = comparisonDetails(original.copy(decisions = listOf(first, first)), original.copy(decisions = listOf(first)))
        assertEquals(ItemChange.AMBIGUOUS, duplicates.single().change)
    }

    @Test
    fun `변경 이력은 성공한 버전마다 남고 팀원에게 비공개 작업을 숨긴다`() {
        session.actor = owner
        val input = command("activities-${UUID.randomUUID()}")
        var record = records.create(input)
        record = records.confirm(ConfirmChangeRecordCommand(record.id, record.version, revision, digest))
        record = records.reopen(record.id, record.version)
        record = records.revise(record.id, record.version, input.copy(title = "수정한 초안"))
        record = records.confirm(ConfirmChangeRecordCommand(record.id, record.version, revision, digest))
        record = records.publish(PublishChangeRecordCommand(record.id, record.version, digest))
        var next = records.create(command("replacement-${UUID.randomUUID()}"))
        next = records.confirm(ConfirmChangeRecordCommand(next.id, next.version, revision, digest))
        next = records.publish(PublishChangeRecordCommand(next.id, next.version, digest))
        record = records.supersede(SupersedeChangeRecordCommand(record.id, record.version, next.id))
        assertEquals(record.id, records.create(input).id)
        assertFailsWith<ConcurrentChangeRecordUpdateException> { records.supersede(SupersedeChangeRecordCommand(record.id, 5, next.id)) }
        val own = activities.list(record.id)
        assertEquals((0L..6L).reversed().toList(), own.items.map { it.version })
        assertEquals(listOf(RecordOperation.SUPERSEDE, RecordOperation.PUBLISH, RecordOperation.CONFIRM,
            RecordOperation.REVISE, RecordOperation.REOPEN, RecordOperation.CONFIRM, RecordOperation.CREATE), own.items.map { it.operation })
        assertTrue(own.items.all { it.actorSubject == owner.subject })
        assertEquals(true, own.historyStartsAtCreation)
        session.actor = other
        val team = activities.list(record.id)
        assertEquals(ActivityVisibility.TEAM, team.visibility)
        assertEquals(listOf(RecordOperation.SUPERSEDE, RecordOperation.PUBLISH), team.items.map { it.operation })
        assertNull(team.historyStartsAtCreation)
        session.actor = owner
        val hidden = records.create(command("private-activity-${UUID.randomUUID()}"))
        records.discard(hidden.id, hidden.version)
        assertEquals(RecordOperation.DISCARD, activities.list(hidden.id).items.first().operation)
        session.actor = other
        assertFailsWith<ChangeRecordOwnershipException> { activities.list(hidden.id) }
    }

    @Test
    fun `이력 저장 실패는 본문 갱신을 취소하고 이전 이력이 없는 기록과 페이지를 구분한다`() {
        session.actor = owner
        val input = command("activity-rollback-${UUID.randomUUID()}")
        var record = records.create(input)
        jdbc.execute("alter table record_activities add constraint reject_test_revision check (operation <> 'REVISE' or record_id <> '${record.id}')")
        try {
            assertFailsWith<org.springframework.dao.DataIntegrityViolationException> { records.revise(record.id, record.version, input.copy(title = "저장되지 않을 수정")) }
            assertEquals(record, records.get(record.id))
            assertEquals(1, activities.list(record.id).items.size)
        } finally { jdbc.execute("alter table record_activities drop constraint reject_test_revision") }
        jdbc.update("delete from record_activities where record_id = ?", record.id.toString())
        assertEquals(false, activities.list(record.id).historyStartsAtCreation)
        repeat(52) { index -> record = records.revise(record.id, record.version, input.copy(title = "수정 $index")) }
        val first = activities.list(record.id)
        val second = activities.list(record.id, assertNotNull(first.nextBeforeVersion))
        assertEquals((1L..52L).reversed().toList(), (first.items + second.items).map { it.version })
        assertNull(second.nextBeforeVersion)
    }
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
        val conflict = assertFailsWith<ChangeRecordRequestConflictException> { records.create(command.copy(title = "다른 생성 요청")) }
        assertEquals("요청 ID ${command.requestId}가 기존 요청과 충돌합니다. 작성자·저장소·내용을 확인하세요.", conflict.message)
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

    @Test
    fun `후속 초안은 원본과 최초 요청을 보존하고 새 근거와 재확인을 요구한다`() {
        session.actor = owner
        val original = records.create(command("source-${UUID.randomUUID()}").copy(
            verifications = listOf(io.intenttrace.record.domain.VerificationRun("test", 0, java.time.Instant.EPOCH,
                java.time.Instant.EPOCH, digest, digest, "이전 검증")),
        ))
        val input = SuccessorDraftCommand("successor-${UUID.randomUUID()}", revision, "d".repeat(64), original.codeAnchors)
        assertFailsWith<IllegalStateException> { records.createSuccessor(original.id, input) }
        records.confirm(ConfirmChangeRecordCommand(original.id, 0, revision, digest))
        records.publish(PublishChangeRecordCommand(original.id, 1, digest))
        val draft = records.createSuccessor(original.id, input)
        assertEquals(original.id, records.get(draft.id).derivedFromRecordId)
        assertEquals(original.decisions, draft.decisions)
        assertEquals(ChangeRecordStatus.DRAFT, draft.status)
        assertNull(draft.targetRevision)
        assertNull(draft.confirmedAt)
        assertTrue(draft.verifications.isEmpty())
        assertFailsWith<IllegalStateException> { records.publish(PublishChangeRecordCommand(draft.id, 0, input.snapshotDigest)) }
        assertEquals(draft.id, records.createSuccessor(original.id, input).id)
        assertFailsWith<ChangeRecordRequestConflictException> { records.createSuccessor(original.id, input.copy(snapshotDigest = digest)) }
        assertEquals(ChangeRecordStatus.PUBLISHED, records.get(original.id).status)
        session.actor = other
        assertFailsWith<ChangeRecordOwnershipException> { records.createSuccessor(original.id, input) }
        assertFailsWith<ChangeRecordOwnershipException> { records.get(draft.id) }
        session.actor = owner
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
