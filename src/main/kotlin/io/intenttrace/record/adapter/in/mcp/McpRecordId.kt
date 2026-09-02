package io.intenttrace.record.adapter.`in`.mcp

import java.util.UUID

internal fun parseChangeRecordId(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("변경 의도 기록 ID는 UUID 형식이어야 합니다.")
    }
