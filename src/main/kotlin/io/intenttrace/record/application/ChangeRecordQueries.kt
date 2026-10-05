package io.intenttrace.record.application

import io.intenttrace.record.domain.ChangeRecord

/** 현재 줄에 연결된 공개 기록이다. 최근 공개 순 [LINE_INTENT_LIMIT]건까지 반환하고 더 있으면 [truncated]다. */
data class LineIntents(val items: List<ChangeRecord>, val truncated: Boolean)

const val LINE_INTENT_LIMIT = 20
