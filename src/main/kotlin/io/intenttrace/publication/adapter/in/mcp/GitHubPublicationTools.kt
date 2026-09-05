package io.intenttrace.publication.adapter.`in`.mcp

import io.intenttrace.publication.adapter.`in`.web.GitHubPublicationResponse
import io.intenttrace.publication.application.PublishChangeRecordToGitHubCommand
import io.intenttrace.publication.application.TeamGitHubPublicationService
import io.intenttrace.publication.application.GitHubPublicationStatus
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class GitHubPublicationTools(
    private val publisher: TeamGitHubPublicationService,
) {
    @McpTool(name = "get_github_publication_status", description = "기록의 최근 GitHub 게시 결과와 최대 20회의 시도 이력을 조회합니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    fun status(@McpToolParam(description = "기록 UUID", required = true) changeRecordId: String,
               @McpToolParam(description = "저장소 소유자", required = true) owner: String,
               @McpToolParam(description = "저장소 이름", required = true) repository: String,
               @McpToolParam(description = "PR 번호", required = true) pullNumber: Int): GitHubPublicationStatus =
        publisher.status(PublishChangeRecordToGitHubCommand(UUID.fromString(changeRecordId), GitHubPullRequestTarget(owner, repository, pullNumber)))

    @McpTool(name = "sync_superseded_record_to_github_pr", description = "사용자가 GitHub 반영을 요청하면 기존 Check Run에 대체 안내를 반영합니다. 새 Check Run은 생성하지 않습니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    fun syncSupersession(@McpToolParam(description = "대체된 기록 UUID", required = true) changeRecordId: String,
                        @McpToolParam(description = "저장소 소유자", required = true) owner: String,
                        @McpToolParam(description = "저장소 이름", required = true) repository: String,
                        @McpToolParam(description = "기존 게시 PR 번호", required = true) pullNumber: Int): GitHubPublicationResponse =
        GitHubPublicationResponse.from(publisher.syncSupersession(PublishChangeRecordToGitHubCommand(UUID.fromString(changeRecordId), GitHubPullRequestTarget(owner, repository, pullNumber))))

    @McpTool(
        name = "publish_change_record_to_github_pr",
        description = "사용자가 명시적으로 요청했을 때 공개 IntentTrace 기록을 같은 HEAD 커밋의 GitHub Pull Request Check Run으로 게시합니다.",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun publish(
        @McpToolParam(description = "공개된 IntentTrace 변경 기록 UUID", required = true)
        changeRecordId: String,
        @McpToolParam(description = "GitHub 저장소 소유자", required = true)
        owner: String,
        @McpToolParam(description = "GitHub 저장소 이름", required = true)
        repository: String,
        @McpToolParam(description = "Pull Request 번호", required = true)
        pullNumber: Int,
    ): GitHubPublicationResponse = GitHubPublicationResponse.from(
        publisher.publish(
            PublishChangeRecordToGitHubCommand(
                changeRecordId = UUID.fromString(changeRecordId),
                target = GitHubPullRequestTarget(owner, repository, pullNumber),
            ),
        ),
    )
}
