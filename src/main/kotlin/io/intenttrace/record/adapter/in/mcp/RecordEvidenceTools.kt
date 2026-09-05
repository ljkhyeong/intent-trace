package io.intenttrace.record.adapter.`in`.mcp

import io.intenttrace.record.application.ChangeIntentHistory
import io.intenttrace.record.application.ChangeIntentHistoryService
import io.intenttrace.record.application.RecordEvidenceCheck
import io.intenttrace.record.application.RecordEvidenceService
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class RecordEvidenceTools(private val evidence: RecordEvidenceService, private val history: ChangeIntentHistoryService) {
    @McpTool(name = "check_change_record_evidence", description = "GitHub 커밋의 코드와 제출한 해시를 비교합니다. 코드 원문은 반환하지 않으며 테스트 실행을 증명하지 않습니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    fun check(@McpToolParam(description = "확인할 기록 UUID", required = true) recordId: String): RecordEvidenceCheck = evidence.check(UUID.fromString(recordId))

    @McpTool(name = "find_related_change_intent", description = "현재 파일의 과거 기록을 찾고 커밋 일치·조상의 동일 파일·미확인 후보를 구분합니다. 과거 테스트는 현재 검증으로 취급하지 마세요.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    fun find(
        @McpToolParam(description = "owner/repository", required = true) repositoryKey: String,
        @McpToolParam(description = "조회하는 전체 커밋 ID", required = true) revision: String,
        @McpToolParam(description = "상대 파일 경로", required = true) path: String,
        @McpToolParam(description = "현재 줄 번호", required = true) line: Int,
        @McpToolParam(description = "직전 응답의 nextCursor") cursor: String? = null,
        @McpToolParam(description = "1~20 사이의 후보 기록 수") limit: Int = 5,
    ): ChangeIntentHistory = history.find(repositoryKey, revision, path, line, cursor, limit)
}
