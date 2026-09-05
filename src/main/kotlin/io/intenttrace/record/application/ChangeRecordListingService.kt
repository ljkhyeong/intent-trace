package io.intenttrace.record.application

import io.intenttrace.record.domain.ChangeRecordStatus
import org.springframework.stereotype.Service

@Service
class ChangeRecordListingService(
    private val catalog: ChangeRecordCatalogService,
    private val records: TeamChangeRecordService,
) {
    fun list(
        repositoryKey: String,
        scope: RecordScope,
        path: String?,
        status: ChangeRecordStatus?,
        authorId: Long?,
        cursor: String?,
        limit: Int?,
        q: String?,
        page: Int?,
        size: Int?,
    ): ChangeRecordPage {
        if (page == null && size == null && scope != RecordScope.MY_DRAFTS) {
            return catalog.list(repositoryKey, scope, path, status, authorId, cursor, limit ?: 20, q)
        }
        require(cursor == null && limit == null && authorId == null && q == null) {
            "페이지 번호 조회에는 cursor·limit·authorId·q를 함께 지정할 수 없습니다."
        }
        val legacyScope = if (scope == RecordScope.TEAM) ChangeRecordListScope.TEAM else ChangeRecordListScope.MY_DRAFTS
        val slice = records.list(ListChangeRecordsQuery(repositoryKey, legacyScope, path, status, page ?: 0, size ?: 20))
        val nextCursor = slice.content.lastOrNull()?.takeIf { slice.hasNext() }
            ?.let { RecordCursor(it.createdAt, it.id).encode() }
        return ChangeRecordPage(slice.content, nextCursor, slice.number, slice.size, slice.hasNext())
    }
}
