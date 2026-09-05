package io.intenttrace.record.adapter.`in`.mcp

import io.intenttrace.record.adapter.`in`.web.ChangeRecordResponse
import io.intenttrace.record.adapter.`in`.web.CreateChangeRecordRequest
import io.intenttrace.record.adapter.`in`.web.ReviseChangeRecordRequest
import io.intenttrace.record.adapter.`in`.web.SuccessorDraftRequest
import io.intenttrace.record.application.ChangeRecordCatalogService
import io.intenttrace.record.application.ChangeRecordPage
import io.intenttrace.record.application.RecordScope
import io.intenttrace.record.application.SupersedeChangeRecordCommand
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.application.ConfirmChangeRecordCommand
import io.intenttrace.record.application.PublishChangeRecordCommand
import io.intenttrace.record.application.TeamChangeRecordService
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validator
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class IntentTraceTools(
    private val records: TeamChangeRecordService,
    private val validator: Validator,
    private val catalog: ChangeRecordCatalogService,
) {
    @McpTool(name = "list_change_records", description = "저장소의 팀 공개 기록 또는 내 비공개 초안을 페이지로 조회합니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    fun list(
        @McpToolParam(description = "owner/repository", required = true) repositoryKey: String,
        @McpToolParam(description = "TEAM은 팀 공개 기록, MINE은 내 초안", required = false) scope: RecordScope? = null,
        @McpToolParam(description = "저장소 상대 파일 경로", required = false) path: String? = null,
        @McpToolParam(description = "조회 범위 내의 기록 상태", required = false) status: ChangeRecordStatus? = null,
        @McpToolParam(description = "팀 공개 목록의 작성자 GitHub 숫자 ID 필터", required = false) authorId: Long? = null,
        @McpToolParam(description = "직전 응답의 nextCursor", required = false) cursor: String? = null,
        @McpToolParam(description = "1~100 사이의 목록 크기", required = false) limit: Int? = null,
        @McpToolParam(description = "제목·요청·판단과 근거에서 찾을 검색어, 최대 200자", required = false) q: String? = null,
    ): ChangeRecordPage = catalog.list(repositoryKey, scope ?: RecordScope.TEAM, path, status, authorId, cursor, limit ?: 20, q)

    @McpTool(name = "create_successor_draft", description = "내 공개 기록의 판단으로 후속 초안을 만듭니다. 새 스냅샷과 코드 근거가 필요하며 검증 결과와 확인 상태는 복사하지 않습니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    fun successor(@McpToolParam(description = "원본 공개 기록 UUID", required = true) recordId: String,
                  @McpToolParam(description = "새 요청 ID와 코드 근거", required = true) request: SuccessorDraftRequest): ChangeRecordResponse {
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) throw ConstraintViolationException(violations)
        return ChangeRecordResponse.from(records.createSuccessor(UUID.fromString(recordId), request.toCommand()))
    }

    @McpTool(name = "revise_change_record", description = "작성자의 DRAFT 내용만 수정합니다. 요청 ID와 저장소는 유지합니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    fun revise(@McpToolParam(description = "기록 UUID", required = true) recordId: String,
               @McpToolParam(description = "현재 버전과 수정할 전체 내용", required = true) request: ReviseChangeRecordRequest): ChangeRecordResponse {
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) throw ConstraintViolationException(violations)
        return ChangeRecordResponse.from(records.revise(UUID.fromString(recordId), request.expectedVersion, request.content.toCommand()))
    }

    @McpTool(name = "reopen_change_record", description = "작성자의 비공개 기록 확인을 취소해 초안으로 돌립니다. 다시 확인해야 공개할 수 있습니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    fun reopen(@McpToolParam(description = "기록 UUID", required = true) recordId: String,
               @McpToolParam(description = "현재 기록 버전", required = true) expectedVersion: Long): ChangeRecordResponse =
        ChangeRecordResponse.from(records.reopen(UUID.fromString(recordId), expectedVersion))

    @McpTool(name = "discard_change_record", description = "작성자의 비공개 초안을 폐기합니다. 폐기 기록은 더 이상 확인하거나 공개할 수 없습니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
    fun discard(@McpToolParam(description = "기록 UUID", required = true) recordId: String,
                @McpToolParam(description = "현재 기록 버전", required = true) expectedVersion: Long): ChangeRecordResponse =
        ChangeRecordResponse.from(records.discard(UUID.fromString(recordId), expectedVersion))

    @McpTool(name = "supersede_change_record", description = "작성자의 공개 기록을 같은 저장소의 새 공개 기록으로 대체합니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    fun supersede(@McpToolParam(description = "기존 기록 UUID", required = true) recordId: String,
                  @McpToolParam(description = "현재 기록 버전", required = true) expectedVersion: Long,
                  @McpToolParam(description = "새 공개 기록 UUID", required = true) replacementRecordId: String): ChangeRecordResponse =
        ChangeRecordResponse.from(records.supersede(SupersedeChangeRecordCommand(UUID.fromString(recordId), expectedVersion, UUID.fromString(replacementRecordId))))

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
    ): ChangeRecordResponse {
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) throw ConstraintViolationException(violations)
        return ChangeRecordResponse.from(records.create(request.toCommand()))
    }

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
    ): ChangeRecordResponse = ChangeRecordResponse.from(records.get(UUID.fromString(recordId)))

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
        @McpToolParam(description = "40자 또는 64자 전체 Git 커밋 ID", required = true)
        immutableRevision: String,
        @McpToolParam(description = "작성자가 확인한 현재 코드의 SHA-256 스냅샷", required = true)
        currentSnapshotDigest: String,
    ): ChangeRecordResponse = ChangeRecordResponse.from(
        records.confirm(
            ConfirmChangeRecordCommand(
                UUID.fromString(recordId),
                expectedVersion,
                immutableRevision,
                currentSnapshotDigest,
            ),
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
        records.publish(
            PublishChangeRecordCommand(
                UUID.fromString(recordId),
                expectedVersion,
                currentSnapshotDigest,
            ),
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
    ): ChangeIntentLookup = ChangeIntentLookup(records.findIntent(repositoryKey, revision, path, line)
        .map { ChangeRecordResponse.from(it, revision) })
}

data class ChangeIntentLookup(val items: List<ChangeRecordResponse>)
