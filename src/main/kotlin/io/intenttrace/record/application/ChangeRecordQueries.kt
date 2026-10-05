package io.intenttrace.record.application

import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.TEAM_VISIBLE_STATUSES

enum class ChangeRecordListScope(val statuses: Set<ChangeRecordStatus>) {
    TEAM(TEAM_VISIBLE_STATUSES),
    MY_DRAFTS(setOf(ChangeRecordStatus.DRAFT, ChangeRecordStatus.AUTHOR_CONFIRMED)),
}

data class ListChangeRecordsQuery(
    val repositoryKey: String,
    val scope: ChangeRecordListScope = ChangeRecordListScope.TEAM,
    val path: String? = null,
    val status: ChangeRecordStatus? = null,
    val page: Int = 0,
    val size: Int = 20,
)

/** 현재 줄에 연결된 공개 기록이다. 최근 공개 순 [LINE_INTENT_LIMIT]건까지 반환하고 더 있으면 [truncated]다. */
data class LineIntents(val items: List<ChangeRecord>, val truncated: Boolean)

const val LINE_INTENT_LIMIT = 20
