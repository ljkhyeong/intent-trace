package io.intenttrace.publication.adapter.`in`.mcp

import io.intenttrace.publication.adapter.`in`.web.RecordPublicationsResponse
import io.intenttrace.publication.application.PublishChangeRecordToGitHubCommand
import io.intenttrace.publication.application.TeamGitHubPublicationService
import io.intenttrace.publication.application.GitHubPublicationStatus
import io.intenttrace.publication.domain.GitHubPublication
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.adapter.`in`.mcp.parseChangeRecordId
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class GitHubPublicationTools(
    private val publisher: TeamGitHubPublicationService,
) {
    @McpTool(name = "get_github_publication_status", description = "기록의 최근 GitHub 게시 결과와 최대 20회의 시도 이력을 조회합니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    fun status(@McpToolParam(description = "기록 UUID") changeRecordId: String,
               @McpToolParam(description = "저장소 소유자") owner: String,
               @McpToolParam(description = "저장소 이름") repository: String,
               @McpToolParam(description = "PR 번호") pullNumber: Int): GitHubPublicationStatus =
        publisher.status(PublishChangeRecordToGitHubCommand(parseChangeRecordId(changeRecordId), GitHubPullRequestTarget(owner, repository, pullNumber)))

    @McpTool(name = "list_record_publications", description = "기록을 게시했거나 게시를 시도한 PR과 최신 결과를 최근 순으로 최대 100개 조회합니다. 대체된 기록은 대체 안내가 필요한 PR을 표시합니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    fun targets(@McpToolParam(description = "기록 UUID") changeRecordId: String): RecordPublicationsResponse =
        RecordPublicationsResponse.from(publisher.targets(parseChangeRecordId(changeRecordId)))

    @McpTool(name = "sync_superseded_record_to_github_pr", description = "사용자가 GitHub 반영을 요청하면 기존 Check Run에 대체 안내를 반영합니다. 새 Check Run은 생성하지 않습니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    fun syncSupersession(@McpToolParam(description = "대체된 기록 UUID") changeRecordId: String,
                        @McpToolParam(description = "저장소 소유자") owner: String,
                        @McpToolParam(description = "저장소 이름") repository: String,
                        @McpToolParam(description = "기존 게시 PR 번호") pullNumber: Int): GitHubPublication =
        publisher.syncSupersession(PublishChangeRecordToGitHubCommand(parseChangeRecordId(changeRecordId), GitHubPullRequestTarget(owner, repository, pullNumber)))

    @McpTool(name = "publish_change_record_to_github_pr", description = "사용자가 명시적으로 요청했을 때 공개 IntentTrace 기록을 같은 HEAD 커밋의 GitHub Pull Request Check Run으로 게시합니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    fun publish(@McpToolParam(description = "공개된 IntentTrace 변경 기록 UUID") changeRecordId: String,
                @McpToolParam(description = "GitHub 저장소 소유자") owner: String,
                @McpToolParam(description = "GitHub 저장소 이름") repository: String,
                @McpToolParam(description = "Pull Request 번호") pullNumber: Int,
                @McpToolParam(description = "true면 변경 후 관련 코드에 PR 줄 주석을 함께 게시합니다. 최대 50개이며 주석이 없는 Check Run에만 추가합니다.", required = false)
                codeAnnotations: Boolean? = null): GitHubPublication =
        publisher.publish(PublishChangeRecordToGitHubCommand(
            parseChangeRecordId(changeRecordId), GitHubPullRequestTarget(owner, repository, pullNumber), codeAnnotations == true))
}
