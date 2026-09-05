package io.intenttrace.record.application

import io.intenttrace.identity.application.CurrentGitHubUserSession
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
    fun append(activity: RecordActivity)
    fun list(recordId: UUID, authorView: Boolean, beforeVersion: Long?, limit: Int): List<RecordActivity>
    fun hasCreation(recordId: UUID): Boolean
}

enum class ActivityVisibility { AUTHOR, TEAM }
data class RecordActivities(
    val recordId: UUID,
    val visibility: ActivityVisibility,
    val items: List<RecordActivity>,
    val nextBeforeVersion: Long?,
    val historyStartsAtCreation: Boolean?,
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
        val authorView = record.createdBy.subject == current.require().actor.subject
        val page = activities.list(recordId, authorView, beforeVersion, 51)
        val items = page.take(50)
        return RecordActivities(recordId, if (authorView) ActivityVisibility.AUTHOR else ActivityVisibility.TEAM,
            items, if (page.size > 50) items.last().version else null,
            if (authorView) activities.hasCreation(recordId) else null)
    }
}
