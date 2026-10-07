package io.intenttrace.record.application

import io.intenttrace.TestCurrentGitHubUserSession
import io.intenttrace.TestGitHubUserAccessGateway
import io.intenttrace.identity.application.RepositoryAccessDeniedException
import io.intenttrace.identity.application.RepositoryAccessService
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.RepositoryRole
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.intenttrace.record.domain.draftRecord

class TeamChangeRecordServiceTest {
    private val repository = InMemoryChangeRecordRepository()
    private val currentSession = TestCurrentGitHubUserSession(owner)
    private val gateway = TestGitHubUserAccessGateway(RepositoryRole.CONTRIBUTOR)
    private val service = TeamChangeRecordService(
        facade = ChangeRecordFacade(repository, SensitiveTextRedactor(), fixedClock, SimpleMeterRegistry()),
        access = RepositoryAccessService(currentSession, gateway),
    )

    @Test
    fun `기여자가 만든 초안의 작성자는 요청값이 아니라 인증 사용자다`() {
        val created = service.create(createCommand(repositoryKey))

        assertEquals(owner, created.createdBy)
    }

    @Test
    fun `읽기 권한 팀원도 다른 작성자의 초안은 볼 수 없다`() {
        repository.record = draft(owner)
        currentSession.actor = teammate
        gateway.role = RepositoryRole.READER

        assertFailsWith<ChangeRecordNotFoundException> {
            service.get(repository.record!!.id)
        }
    }

    @Test
    fun `저장소 권한이 없으면 ID 조회에서 저장소 이름 대신 기록 없음으로 응답한다`() {
        repository.record = draft(owner).copy(status = ChangeRecordStatus.PUBLISHED)
        currentSession.actor = teammate
        gateway.role = null

        val failure = assertFailsWith<ChangeRecordNotFoundException> { service.get(repository.record!!.id) }
        assertFalse(failure.message.orEmpty().contains(repository.record!!.repositoryKey))
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
            service.create(createCommand(repositoryKey))
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

        assertEquals(1, gateway.roleChecks.get())
        assertEquals(ChangeRecordStatus.SUPERSEDED, repository.records[current.id]?.status)
    }

    private fun draft(actor: ActorIdentity) = draftRecord(actor, repositoryKey)

    private class InMemoryChangeRecordRepository : ChangeRecordRepository {
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
            limit: Int,
        ): List<ChangeRecord> = listOfNotNull(record).filter {
            it.repositoryKey == repositoryKey &&
                it.targetRevision == targetRevision &&
                it.codeAnchors.any { anchor ->
                    anchor.relativePath == relativePath && line in anchor.startLine..anchor.endLine
                }
        }

        override fun saveNew(record: ChangeRecord): ChangeRecord = record.also { this.record = it }

        override fun update(record: ChangeRecord, expectedVersion: Long, activity: RecordActivity): ChangeRecord = record.also {
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
