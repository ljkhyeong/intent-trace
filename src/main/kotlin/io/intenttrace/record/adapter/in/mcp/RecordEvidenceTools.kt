package io.intenttrace.record.adapter.`in`.mcp

import io.intenttrace.record.application.ChangeRecordComparison
import io.intenttrace.record.application.RecordComparisonService
import io.intenttrace.record.application.ChangeIntentHistory
import io.intenttrace.record.application.ChangeIntentHistoryService
import io.intenttrace.record.application.RecordEvidenceCheck
import io.intenttrace.record.application.RecordEvidenceService
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class RecordEvidenceTools(private val evidence: RecordEvidenceService, private val history: ChangeIntentHistoryService, private val comparison: RecordComparisonService) {
    @McpTool(name = "compare_change_record", description = "원본과 새 기록의 구현 결정·출처·관련 코드·검증·질문을 비교합니다. 비공개 기록은 작성자만 읽습니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    fun compare(@McpToolParam(description = "원본에서 만든 새 기록의 UUID", required = true) recordId: String): ChangeRecordComparison = comparison.compare(parseChangeRecordId(recordId))

    @McpTool(name = "check_change_record_evidence", description = "GitHub 커밋의 코드와 제출한 해시를 비교합니다. 코드 원문은 반환하지 않으며 테스트 실행을 증명하지 않습니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    fun check(@McpToolParam(description = "확인할 기록 UUID", required = true) recordId: String): RecordEvidenceCheck = evidence.check(parseChangeRecordId(recordId))

    @McpTool(name = "find_related_change_intent", description = "현재 파일의 과거 기록을 찾고 커밋 일치·조상의 동일 파일·코드 일치 미확인 기록을 구분합니다. 과거 테스트는 현재 검증으로 취급하지 마세요. resumeBlocked=true이면 같은 커서를 자동 반복하지 말고 서버 조회 제한과 GitHub 지연 확인을 안내하세요. CANCELLED는 사용자가 재개를 요청한 뒤 다시 조회하세요.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    fun find(
        @McpToolParam(description = "owner/repository", required = true) repositoryKey: String,
        @McpToolParam(description = "조회할 커밋 해시(40자 또는 64자)", required = true) revision: String,
        @McpToolParam(description = "상대 파일 경로", required = true) path: String,
        @McpToolParam(description = "현재 줄 번호", required = true) line: Int,
        @McpToolParam(description = "직전 응답의 nextCursor", required = false) cursor: String? = null,
        @McpToolParam(description = "한 번에 조회할 기록 수(1~20)", required = false) limit: Int? = null,
        @McpToolParam(description = "확인하지 못한 기록의 UUID. cursor와 함께 지정할 수 없습니다", required = false) retryRecordId: String? = null,
    ): ChangeIntentHistory = history.find(repositoryKey, revision, path, line, cursor, limit ?: 5, retryRecordId?.let(::parseChangeRecordId))
}
