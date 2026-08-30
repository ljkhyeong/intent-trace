package io.intenttrace.record.adapter.`in`.web

import io.intenttrace.record.application.ChangeRecordSummary
import org.springframework.data.domain.Slice

data class ChangeRecordListResponse(
    val items: List<ChangeRecordSummary>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean,
) {
    companion object {
        fun from(slice: Slice<ChangeRecordSummary>) = ChangeRecordListResponse(
            slice.content, slice.number, slice.size, slice.hasNext(),
        )
    }
}
