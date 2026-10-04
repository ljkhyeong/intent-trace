package io.intenttrace.record.application

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
