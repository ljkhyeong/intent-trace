package io.intenttrace.connection.adapter.`in`

import io.intenttrace.connection.application.ConnectionDiagnosis
import io.intenttrace.connection.application.ConnectionDiagnostics
import io.intenttrace.publication.application.PublicationPreflight
import io.intenttrace.publication.application.PublicationPreflightService
import org.springframework.web.bind.annotation.PostMapping
import io.intenttrace.publication.application.PullRequestOverview
import io.intenttrace.publication.application.PullRequestOverviewService
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class ConnectionController(private val diagnostics: ConnectionDiagnostics, private val overview: PullRequestOverviewService, private val preflight: PublicationPreflightService) {
    @PostMapping("/api/v1/publication-preflight")
    fun preflight(@RequestParam repositoryKey: String): PublicationPreflight = preflight.check(repositoryKey)

    @GetMapping("/api/v1/connection-diagnostics")
    fun diagnose(@RequestParam repositoryKey: String, @RequestParam(required = false) revision: String?,
                 @RequestParam(required = false) pullNumber: Int?): ConnectionDiagnosis = diagnostics.diagnose(repositoryKey, revision, pullNumber)

    @GetMapping("/api/v1/github-pull-request/records")
    fun records(@RequestParam owner: String, @RequestParam repository: String, @RequestParam pullNumber: Int,
                @RequestParam(required = false) cursor: String?, @RequestParam(defaultValue = "20") limit: Int): PullRequestOverview =
        overview.overview(GitHubPullRequestTarget(owner, repository, pullNumber), cursor, limit)
}

@Component
class ConnectionTools(private val diagnostics: ConnectionDiagnostics, private val overview: PullRequestOverviewService, private val preflight: PublicationPreflightService) {
    @McpTool(name = "check_publication_credentials", description = "저장소 관리자만 App 키·설치·발급 권한을 사전 점검합니다. 메모리에서 token을 발급하며 Check Run은 만들지 않습니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = true))
    fun preflight(@McpToolParam(description = "owner/repository", required = true) repositoryKey: String): PublicationPreflight = preflight.check(repositoryKey)

    @McpTool(name = "diagnose_connection", description = "사용자·저장소 권한, 선택 PR·코드 읽기와 게시 설정을 진단합니다. 실제 게시나 테스트 실행은 하지 않습니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    fun diagnose(@McpToolParam(description = "owner/repository", required = true) repositoryKey: String,
                 @McpToolParam(description = "코드 읽기를 확인할 커밋 해시(40자 또는 64자)", required = false) revision: String? = null,
                 @McpToolParam(description = "조회할 PR 번호", required = false) pullNumber: Int? = null): ConnectionDiagnosis =
        diagnostics.diagnose(repositoryKey, revision, pullNumber)

    @McpTool(name = "list_pull_request_records", description = "PR에 게시했거나 게시를 시도한 팀 공개 기록과 현재 HEAD 일치 여부를 조회합니다.", generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    fun records(@McpToolParam(description = "GitHub 저장소 소유자", required = true) owner: String,
                @McpToolParam(description = "GitHub 저장소 이름", required = true) repository: String,
                @McpToolParam(description = "PR 번호", required = true) pullNumber: Int,
                @McpToolParam(description = "직전 응답의 nextCursor", required = false) cursor: String? = null,
                @McpToolParam(description = "1~100, 기본 20", required = false) limit: Int? = null): PullRequestOverview =
        overview.overview(GitHubPullRequestTarget(owner, repository, pullNumber), cursor, limit ?: 20)
}
