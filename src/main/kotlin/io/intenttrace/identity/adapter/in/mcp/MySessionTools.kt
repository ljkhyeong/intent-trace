package io.intenttrace.identity.adapter.`in`.mcp

import io.intenttrace.identity.application.MySessionService
import io.intenttrace.identity.application.MySessions
import io.intenttrace.identity.application.SessionRevocation
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class MySessionTools(private val sessions: MySessionService) {
    @McpTool(name = "list_my_sessions", description = "내 IntentTrace 연결의 ID·생성·최근 사용·만료 시각을 조회합니다. 토큰은 반환하지 않습니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    fun list(): MySessions = sessions.list()

    @McpTool(name = "revoke_my_session", description = "사용자가 종료를 요청한 내 IntentTrace 연결을 종료합니다. ID 생략 시 현재 연결을 종료합니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    fun revoke(@McpToolParam(description = "목록에서 확인한 내 세션 ID, 생략 시 현재 연결", required = false) sessionId: String? = null): SessionRevocation =
        sessions.revoke(sessionId?.let(UUID::fromString))

    @McpTool(name = "revoke_all_my_sessions", description = "사용자가 전체 연결 종료를 요청했을 때 내 모든 IntentTrace 연결을 종료합니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    fun revokeAll(): SessionRevocation = sessions.revokeAll()
}
