package io.intenttrace.record.application

import io.intenttrace.identity.application.RepositoryAccessService
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.GitRevision
import io.intenttrace.record.domain.requireRepositoryRelativePath
import org.springframework.stereotype.Service

enum class IntentMatch { EXACT_REVISION, ANCESTOR_UNCHANGED_FILE, RELATED_UNVERIFIED }

data class HistoricalIntent(
    val record: ChangeRecordSummary,
    val sourceRevision: String,
    val side: CodeSide,
    val match: IntentMatch,
    val verificationAppliesToQuery: Boolean,
)

data class ChangeIntentHistory(val queryRevision: String, val path: String, val items: List<HistoricalIntent>, val nextCursor: String?)

@Service
class ChangeIntentHistoryService(
    private val catalog: ChangeRecordCatalogService,
    private val facade: ChangeRecordFacade,
    private val access: RepositoryAccessService,
    private val gateway: GitEvidenceGateway,
) {
    fun find(repositoryKey: String, revision: String, path: String, line: Int, cursor: String? = null, limit: Int = 5): ChangeIntentHistory {
        val repository = GitHubRepository.parse(repositoryKey)
        val queryRevision = GitRevision.parse(revision).value
        requireRepositoryRelativePath(path)
        require(line > 0 && limit in 1..20) { "줄은 양수이고 이전 기록 조회 크기는 1~20이어야 합니다." }
        access.requireReader(repository.key)
        val page = catalog.list(repository.key, path = path, cursor = cursor, limit = limit)
        val snapshots = mutableMapOf<String, GitEvidenceSnapshot>()
        fun snapshot(ref: String) = snapshots.getOrPut(ref) { gateway.snapshot(repository, ref) }
        val targetEntry by lazy { snapshot(queryRevision).entries[path] }
        val ancestry = mutableMapOf<String, Boolean>()
        val items = page.items.flatMap { summary ->
            val record = facade.get(summary.id)
            record.codeAnchors.filter { it.relativePath == path }.mapNotNull { anchor ->
                val source = if (anchor.side == CodeSide.BASE) record.baseRevision else record.targetRevision
                if (source == null) return@mapNotNull null
                val inRange = line in anchor.startLine..anchor.endLine
                val match = when {
                    source == queryRevision && inRange -> IntentMatch.EXACT_REVISION
                    source != queryRevision && inRange &&
                        ancestry.getOrPut(source) { gateway.isAncestor(repository, source, queryRevision) } &&
                        targetEntry?.type == "blob" && snapshot(source).entries[path]?.let { it.type == "blob" && it.sha == targetEntry?.sha } == true ->
                        IntentMatch.ANCESTOR_UNCHANGED_FILE
                    else -> IntentMatch.RELATED_UNVERIFIED
                }
                HistoricalIntent(summary, source, anchor.side, match,
                    match == IntentMatch.EXACT_REVISION && record.targetRevision == queryRevision)
            }.distinctBy { Triple(it.sourceRevision, it.side, it.match) }
        }
        return ChangeIntentHistory(queryRevision, path, items, page.nextCursor)
    }
}
