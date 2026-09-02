package io.intenttrace.record.application

import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserAccessGateway
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.identity.application.RepositoryAccessDeniedException
import io.intenttrace.identity.application.RepositoryAccessService
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TeamChangeRecordServiceTest {
    private val repository = InMemoryChangeRecordRepository()
    private val currentSession = TestCurrentSession(owner)
    private val gateway = TestGitHubUserAccessGateway(RepositoryRole.CONTRIBUTOR)
    private val service = TeamChangeRecordService(
        facade = ChangeRecordFacade(repository, SensitiveTextRedactor(), fixedClock),
        access = RepositoryAccessService(currentSession, gateway),
    )

    @Test
    fun `기여자가 만든 초안의 작성자는 요청값이 아니라 인증 사용자다`() {
        val created = service.create(createCommand("team-create"))

        assertEquals(owner, created.createdBy)
    }

    @Test
    fun `읽기 권한 팀원도 다른 작성자의 초안은 볼 수 없다`() {
        repository.record = draft(owner)
        currentSession.actor = teammate
        gateway.role = RepositoryRole.READER

        assertFailsWith<ChangeRecordOwnershipException> {
            service.get(repository.record!!.id)
        }
    }

    @Test
    fun `읽기 권한 팀원은 공개된 팀 기록을 볼 수 있다`() {
        repository.record = draft(owner).copy(status = ChangeRecordStatus.PUBLISHED)
        currentSession.actor = teammate
        gateway.role = RepositoryRole.READER

        assertEquals(repository.record, service.get(repository.record!!.id))
    }

    @Test
    fun `읽기 권한만 있으면 초안을 만들 수 없다`() {
        gateway.role = RepositoryRole.READER

        assertFailsWith<RepositoryAccessDeniedException> {
            service.create(createCommand("reader-create"))
        }
    }

    @Test
    fun `초안 확인은 같은 기록을 한 번만 조회한다`() {
        repository.record = draft(owner)

        service.confirm(
            ConfirmChangeRecordCommand(
                recordId = repository.record!!.id,
                expectedVersion = 0,
                immutableRevision = "b".repeat(40),
                currentSnapshotDigest = "a".repeat(64),
            ),
        )

        assertEquals(1, repository.findByIdCount)
    }

    @Test
    fun `같은 저장소의 공개 기록 대체는 권한을 한 번만 확인한다`() {
        val current = draft(owner).copy(status = ChangeRecordStatus.PUBLISHED)
        val replacement = draft(owner).copy(id = UUID.randomUUID(), status = ChangeRecordStatus.PUBLISHED)
        repository.records[current.id] = current
        repository.records[replacement.id] = replacement

        service.supersede(SupersedeChangeRecordCommand(current.id, current.version, replacement.id))

        assertEquals(1, gateway.repositoryRoleCount)
        assertEquals(ChangeRecordStatus.SUPERSEDED, repository.records[current.id]?.status)
    }

    private fun createCommand(requestId: String) = CreateChangeRecordCommand(
        requestId = requestId,
        repositoryKey = repositoryKey,
        snapshotDigest = "a".repeat(64),
        title = "팀 인증 기록",
        requestSummary = "인증 사용자를 작성자로 저장한다.",
        decisions = listOf(Decision("작성자 입력을 받지 않는다.", null, PurposeSource.STATED_BY_USER)),
        codeAnchors = listOf(CodeAnchor("src/App.kt", "App", 1, 2, "b".repeat(64))),
        verifications = emptyList(),
        openQuestions = emptyList(),
    )

    private fun draft(actor: ActorIdentity) = ChangeRecord(
        id = UUID.randomUUID(),
        requestId = "seeded",
        repositoryKey = repositoryKey,
        targetRevision = null,
        snapshotDigest = "a".repeat(64),
        title = "초안",
        requestSummary = "팀 공개 전 기록",
        status = ChangeRecordStatus.DRAFT,
        createdBy = actor,
        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
        confirmedAt = null,
        publishedAt = null,
        supersededBy = null,
        version = 0,
        decisions = listOf(Decision("초안으로 둔다.", null, PurposeSource.STATED_BY_USER)),
        codeAnchors = listOf(CodeAnchor("src/App.kt", "App", 1, 2, "b".repeat(64))),
        verifications = emptyList(),
        openQuestions = emptyList(),
    )

    private class TestCurrentSession(var actor: ActorIdentity) : CurrentGitHubUserSession {
        override fun require(): GitHubUserSession = GitHubUserSession(actor, "user-token")
    }

    private class TestGitHubUserAccessGateway(var role: RepositoryRole?) : GitHubUserAccessGateway {
        var repositoryRoleCount = 0

        override fun authenticate(accessToken: String): ActorIdentity = error("사용하지 않는 테스트 경로")

        override fun repositoryRole(
            accessToken: String,
            actor: ActorIdentity,
            repository: GitHubRepository,
        ): RepositoryRole? {
            repositoryRoleCount += 1
            return role
        }
    }

    private class InMemoryChangeRecordRepository : ChangeRecordRepository {
        override fun findSummaries(
            repositoryKey: String,
            statuses: Set<ChangeRecordStatus>,
            authorSubject: String?,
            relativePath: String?,
            pageable: Pageable,
        ): Slice<ChangeRecordSummary> = error("사용하지 않는 테스트 경로")

        var record: ChangeRecord? = null
        val records = mutableMapOf<UUID, ChangeRecord>()
        var findByIdCount: Int = 0

        override fun findById(id: UUID): ChangeRecord? {
            findByIdCount += 1
            return records[id] ?: record?.takeIf { it.id == id }
        }

        override fun findByRequestId(requestId: String): ChangeRecord? = record?.takeIf { it.requestId == requestId }

        override fun findByIdsForUpdate(ids: Set<UUID>): List<ChangeRecord> = ids.mapNotNull(::findById)

        override fun findPublishedByAnchor(
            repositoryKey: String,
            targetRevision: String,
            relativePath: String,
            line: Int,
        ): List<ChangeRecord> = listOfNotNull(record).filter {
            it.repositoryKey == repositoryKey &&
                it.targetRevision == targetRevision &&
                it.codeAnchors.any { anchor ->
                    anchor.relativePath == relativePath && line in anchor.startLine..anchor.endLine
                }
        }

        override fun saveNew(record: ChangeRecord): ChangeRecord = record.also { this.record = it }

        override fun update(record: ChangeRecord, expectedVersion: Long): ChangeRecord = record.also {
            this.record = it
            records[it.id] = it
        }
    }

    companion object {
        private const val repositoryKey = "acme/intent-trace"
        private val fixedClock = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC)
        private val owner = ActorIdentity.github(42, "lim")
        private val teammate = ActorIdentity.github(84, "teammate")
    }
}
