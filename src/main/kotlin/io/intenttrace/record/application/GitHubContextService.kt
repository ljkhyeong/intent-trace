package io.intenttrace.record.application

import io.intenttrace.identity.application.RepositoryAccessService
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.record.domain.GitRevision
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

enum class GitHubRequestKind { ISSUE, PULL_REQUEST }

class GitHubContextNotFoundException : RuntimeException("GitHub 자료가 없거나 열람할 수 없습니다. 저장소·이슈·PR 주소와 GitHub App 권한을 확인해 주세요.")

class GitHubContextPermissionException : RuntimeException("GitHub 자료 조회가 거부됐습니다. GitHub App의 Issues·Pull requests·Actions 읽기 권한과 저장소 접근을 확인해 주세요.")

data class GitHubRequestContent(
    val kind: GitHubRequestKind, val title: String, val body: String?, val url: String, val updatedAt: Instant,
)

data class GitHubRequestContext(
    val repositoryKey: String, val number: Int, val kind: GitHubRequestKind, val sourceUrl: String,
    val title: String, val requestSummary: String, val truncated: Boolean, val updatedAt: Instant, val fetchedAt: Instant,
    val authorConfirmed: Boolean = false,
)

data class GitHubActionsRun(
    val id: Long, val attempt: Int, val name: String, val headRevision: String, val event: String,
    val status: String, val conclusion: String?, val startedAt: Instant?, val updatedAt: Instant, val url: String,
)

data class GitHubActionsPage(val totalCount: Int, val items: List<GitHubActionsRun>)

data class GitHubActionsResults(
    val repositoryKey: String, val revision: String, val items: List<GitHubActionsRun>, val nextPage: Int?,
    val searchLimited: Boolean, val fetchedAt: Instant,
    val source: String = "GITHUB_ACTIONS", val snapshotVerified: Boolean = false,
)

interface GitHubContextGateway {
    fun request(repository: GitHubRepository, number: Int): GitHubRequestContent
    fun actions(repository: GitHubRepository, revision: String, page: Int): GitHubActionsPage
}

@Service
class GitHubContextService(
    private val access: RepositoryAccessService,
    private val gateway: GitHubContextGateway,
    private val redactor: SensitiveTextRedactor,
    private val clock: Clock,
) {
    fun request(repositoryKey: String, number: Int): GitHubRequestContext {
        val repository = GitHubRepository.parse(repositoryKey)
        require(number > 0) { "이슈 또는 PR 번호는 양수여야 합니다." }
        access.requireReader(repository.key)
        val content = gateway.request(repository, number)
        val title = redactor.redact(content.title).trim()
        val body = redactor.redact(content.body.orEmpty()).trim()
        // 원문을 먼저 정제해야 길이 제한에 걸린 비밀값의 일부가 남지 않는다.
        val source = "출처: ${content.url}"
        val excerpt = body.ifBlank { title }
        val capacity = 2000 - source.length - 2
        return GitHubRequestContext(repository.key, number, content.kind, content.url, title.take(200),
            "$source\n\n${excerpt.take(capacity)}", title.length > 200 || excerpt.length > capacity,
            content.updatedAt, Instant.now(clock))
    }

    fun actions(repositoryKey: String, revision: String, page: Int = 1): GitHubActionsResults {
        val repository = GitHubRepository.parse(repositoryKey)
        val ref = GitRevision.parse(revision).value
        require(page in 1..50) { "Actions 조회 페이지는 1~50이어야 합니다." }
        access.requireReader(repository.key)
        val result = gateway.actions(repository, ref, page)
        return GitHubActionsResults(repository.key, ref,
            result.items.map { it.copy(name = redactor.redact(it.name).take(200)) },
            (page + 1).takeIf { page < 50 && result.items.size == 20 && page * 20 < result.totalCount },
            result.totalCount > 1000, Instant.now(clock))
    }
}
