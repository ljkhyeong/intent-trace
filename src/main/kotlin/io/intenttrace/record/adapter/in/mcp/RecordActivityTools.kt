package io.intenttrace.record.adapter.`in`.mcp

import io.intenttrace.record.application.RecordActivities
import io.intenttrace.record.application.RecordActivityService
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class RecordActivityTools(private val activities: RecordActivityService) {
    @McpTool(name = "list_record_activities", description = "기록의 처리 시각·작업·버전을 조회합니다. 작성자는 전체 작업을, 팀원은 공개·대체 작업만 읽습니다. 본문 이전 버전은 저장하지 않습니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    fun list(@McpToolParam(description = "기록 UUID", required = true) recordId: String,
        @McpToolParam(description = "직전 응답의 nextBeforeVersion", required = false) beforeVersion: Long? = null): RecordActivities =
        activities.list(UUID.fromString(recordId), beforeVersion)
}
