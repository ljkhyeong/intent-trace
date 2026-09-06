package io.intenttrace.record.application

import io.intenttrace.record.domain.ChangeRecordStatus

enum class ChangeRecordListScope(val statuses: Set<ChangeRecordStatus>) {
    TEAM(setOf(ChangeRecordStatus.PUBLISHED, ChangeRecordStatus.SUPERSEDED)),
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
