package io.intenttrace.publication.adapter.`in`.mcp

import io.intenttrace.publication.adapter.`in`.web.GitHubPublicationResponse
import io.intenttrace.publication.application.PublishChangeRecordToGitHub
import io.intenttrace.publication.application.PublishChangeRecordToGitHubCommand
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class GitHubPublicationTools(
    private val publisher: PublishChangeRecordToGitHub,
) {
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
