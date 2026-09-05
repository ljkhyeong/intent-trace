package io.intenttrace.record.application

import io.intenttrace.identity.application.RepositoryAccessService
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.requireRepositoryRelativePath
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Base64
import java.util.UUID

enum class RecordScope { MINE, TEAM, MY_DRAFTS }

data class ChangeRecordSummary(
    val id: UUID,
    val title: String,
    val requestSummary: String,
    val repositoryKey: String,
    val targetRevision: String?,
    val status: ChangeRecordStatus,
    val createdBy: ActorIdentity,
    val createdAt: Instant,
    val supersededBy: UUID?,
    val version: Long,
    val publishedAt: Instant? = null,
)

data class ChangeRecordPage(
    val items: List<ChangeRecordSummary>,
    val nextCursor: String?,
    val page: Int? = 0,
    val size: Int = 20,
    val hasNext: Boolean = nextCursor != null,
)

data class RecordCursor(val createdAt: Instant, val id: UUID) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$createdAt|$id".toByteArray(Charsets.UTF_8))

    companion object {
        fun parse(value: String): RecordCursor = try {
            require(value.length <= 256)
            val parts = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8).split('|')
            require(parts.size == 2)
            RecordCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]))
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("기록 목록 커서가 올바르지 않습니다.")
        }
    }
}

data class RecordCatalogQuery(
    val repositoryKey: String,
    val statuses: Set<ChangeRecordStatus>,
    val authorSubject: String?,
    val path: String?,
    val cursor: RecordCursor?,
    val limit: Int,
    val keyword: String? = null,
    val pullNumber: Int? = null,
)

interface ChangeRecordCatalog {
    fun search(query: RecordCatalogQuery): List<ChangeRecordSummary>
}

@Service
class ChangeRecordCatalogService(
    private val catalog: ChangeRecordCatalog,
    private val access: RepositoryAccessService,
) {
    fun list(
        repositoryKey: String,
        scope: RecordScope = RecordScope.TEAM,
        path: String? = null,
        status: ChangeRecordStatus? = null,
        authorId: Long? = null,
        cursor: String? = null,
        limit: Int = 20,
        q: String? = null,
        pullNumber: Int? = null,
    ): ChangeRecordPage {
        require(pullNumber == null || pullNumber > 0) { "PR 번호는 양수여야 합니다." }
        require(limit in 1..100) { "목록 크기는 1~100이어야 합니다." }
        require(q == null || q.length <= 200) { "검색어는 200자 이하여야 합니다." }
        val keyword = q?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedPath = path?.let(::requireRepositoryRelativePath)
        require(authorId == null || authorId > 0) { "작성자 GitHub ID는 양수여야 합니다." }
        val key = GitHubRepository.parse(repositoryKey).key
        val actor = access.requireReader(key)
        val allowed = when (scope) {
            RecordScope.MINE, RecordScope.MY_DRAFTS -> setOf(ChangeRecordStatus.DRAFT, ChangeRecordStatus.AUTHOR_CONFIRMED, ChangeRecordStatus.DISCARDED)
            RecordScope.TEAM -> setOf(ChangeRecordStatus.PUBLISHED, ChangeRecordStatus.SUPERSEDED)
        }
        require(status == null || status in allowed) { "조회 범위에 맞지 않는 기록 상태입니다." }
        require(scope == RecordScope.TEAM || authorId == null) { "내 초안 목록에는 다른 작성자 필터를 지정할 수 없습니다." }
        val statuses = status?.let(::setOf) ?: (allowed - ChangeRecordStatus.DISCARDED)
        val items = catalog.search(
            RecordCatalogQuery(
                key, statuses,
                if (scope != RecordScope.TEAM) actor.subject else authorId?.let { "github:$it" },
                normalizedPath, cursor?.let(RecordCursor::parse), limit + 1, keyword, pullNumber,
            ),
        )
        val page = items.take(limit)
        return ChangeRecordPage(page, if (items.size > limit) page.last().let { RecordCursor(it.createdAt, it.id).encode() } else null,
            page = if (cursor == null) 0 else null, size = limit)
    }
}
