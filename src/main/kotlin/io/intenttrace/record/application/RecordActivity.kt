package io.intenttrace.record.application

import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.domain.ChangeRecordStatus
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

enum class RecordOperation { CREATE, REVISE, CONFIRM, REOPEN, PUBLISH, DISCARD, SUPERSEDE }

data class RecordActivity(
    val recordId: UUID,
    val operation: RecordOperation,
    val actorSubject: String,
    val previousVersion: Long?,
    val version: Long,
    val previousStatus: ChangeRecordStatus?,
    val status: ChangeRecordStatus,
    val occurredAt: Instant,
)

interface RecordActivityStore {
    fun list(recordId: UUID, visibility: ActivityVisibility, beforeVersion: Long?, limit: Int): List<RecordActivity>
}

enum class ActivityVisibility { AUTHOR, TEAM }
/** 상태 변경은 작성자만 하므로 [author]로 처리자의 표시 이름을 찾는다. 이력에는 처리자 subject만 저장한다. */
data class RecordActivities(
    val recordId: UUID,
    val visibility: ActivityVisibility,
    val items: List<RecordActivity>,
    val nextBeforeVersion: Long?,
    val author: ActorIdentity,
)

@Service
class RecordActivityService(
    private val records: TeamChangeRecordService,
    private val current: CurrentGitHubUserSession,
    private val activities: RecordActivityStore,
) {
    fun list(recordId: UUID, beforeVersion: Long? = null): RecordActivities {
        require(beforeVersion == null || beforeVersion >= 0) { "이력 조회 버전은 0 이상이어야 합니다." }
        val record = records.get(recordId)
        val visibility = if (record.createdBy.subject == current.require().actor.subject) ActivityVisibility.AUTHOR else ActivityVisibility.TEAM
        val page = activities.list(recordId, visibility, beforeVersion, 51)
        val items = page.take(50)
        return RecordActivities(recordId, visibility, items, if (page.size > 50) items.last().version else null, record.createdBy)
    }
}
