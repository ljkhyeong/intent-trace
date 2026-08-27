package io.intenttrace.record.adapter.`in`.mcp

import io.intenttrace.record.adapter.`in`.web.ChangeRecordResponse
import io.intenttrace.record.adapter.`in`.web.ConfirmChangeRecordRequest
import io.intenttrace.record.adapter.`in`.web.CreateChangeRecordRequest
import io.intenttrace.record.adapter.`in`.web.PublishChangeRecordRequest
import io.intenttrace.record.application.ChangeRecordFacade
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class IntentTraceTools(
    private val facade: ChangeRecordFacade,
) {
    @McpTool(
        name = "create_change_record",
        description = "현재 코드 변경의 요청, 판단, 코드 근거, 실제 검증을 비공개 초안으로 기록합니다. 원문 대화나 숨은 추론은 전달하지 마세요.",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    )
    fun create(
        @McpToolParam(description = "작성자가 검토할 구조화된 변경 의도 초안", required = true)
        request: CreateChangeRecordRequest,
    ): ChangeRecordResponse = ChangeRecordResponse.from(facade.create(request.toCommand()))

    @McpTool(
        name = "get_change_record",
        description = "변경 의도 기록 한 건을 조회합니다.",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    )
    fun get(
        @McpToolParam(description = "변경 의도 기록 UUID", required = true)
        recordId: String,
    ): ChangeRecordResponse = ChangeRecordResponse.from(facade.get(UUID.fromString(recordId)))

    @McpTool(
        name = "confirm_change_record",
        description = "작성자가 검토한 초안을 전체 Git 커밋과 현재 코드 스냅샷에 묶어 확인합니다.",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = false,
        ),
    )
    fun confirm(
        @McpToolParam(description = "변경 의도 기록 UUID", required = true)
        recordId: String,
        @McpToolParam(description = "낙관적 잠금용 현재 기록 버전", required = true)
        expectedVersion: Long,
        @McpToolParam(description = "초안을 만든 작성자 식별자", required = true)
        author: String,
        @McpToolParam(description = "40자 또는 64자 전체 Git 커밋 ID", required = true)
        immutableRevision: String,
        @McpToolParam(description = "작성자가 확인한 현재 코드의 SHA-256 스냅샷", required = true)
        currentSnapshotDigest: String,
    ): ChangeRecordResponse = ChangeRecordResponse.from(
        facade.confirm(
            ConfirmChangeRecordRequest(
                expectedVersion,
                author,
                immutableRevision,
                currentSnapshotDigest,
            ).toCommand(UUID.fromString(recordId)),
        ),
    )

    @McpTool(
        name = "publish_change_record",
        description = "작성자가 확인했고 현재 코드 스냅샷과 일치하는 기록을 팀에 공개합니다.",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = false,
        ),
    )
    fun publish(
        @McpToolParam(description = "변경 의도 기록 UUID", required = true)
        recordId: String,
        @McpToolParam(description = "낙관적 잠금용 현재 기록 버전", required = true)
        expectedVersion: Long,
        @McpToolParam(description = "공개할 현재 코드의 SHA-256 스냅샷", required = true)
        currentSnapshotDigest: String,
    ): ChangeRecordResponse = ChangeRecordResponse.from(
        facade.publish(
            PublishChangeRecordRequest(expectedVersion, currentSnapshotDigest)
                .toCommand(UUID.fromString(recordId)),
        ),
    )

    @McpTool(
        name = "find_change_intent",
        description = "정확한 저장소, Git 커밋, 파일, 줄에 연결된 공개 변경 의도를 찾습니다.",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    )
    fun find(
        @McpToolParam(description = "저장소 식별자", required = true)
        repositoryKey: String,
        @McpToolParam(description = "전체 Git 커밋 ID", required = true)
        revision: String,
        @McpToolParam(description = "저장소 기준 상대 파일 경로", required = true)
        path: String,
        @McpToolParam(description = "조회할 1부터 시작하는 줄 번호", required = true)
        line: Int,
    ): List<ChangeRecordResponse> = facade.findIntent(repositoryKey, revision, path, line)
        .map(ChangeRecordResponse::from)
}
