package io.intenttrace.record.application

import io.intenttrace.identity.application.RepositoryAccessService
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.GitRevision
import io.intenttrace.record.domain.requireRepositoryRelativePath
import org.springframework.stereotype.Service

enum class IntentMatch { EXACT_REVISION, ANCESTOR_UNCHANGED_FILE, ANCESTOR_RENAMED_FILE, ANCESTOR_UNCHANGED_LINES, ANCESTOR_MOVED_LINES, RELATED_UNVERIFIED }

data class HistoricalIntent(
    val record: ChangeRecordSummary,
    val sourceRevision: String,
    val side: CodeSide,
    val match: IntentMatch,
    val verificationAppliesToQuery: Boolean,
    val sourcePath: String,
    val sourceStartLine: Int,
    val sourceEndLine: Int,
    val currentStartLine: Int?,
    val currentEndLine: Int?,
)

data class ChangeIntentHistory(
    val queryRevision: String, val path: String, val items: List<HistoricalIntent>, val nextCursor: String?,
    val scannedRecords: Int,
)

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
        // 이름이 바뀐 기록도 찾도록 저장소 후보를 제한된 페이지 단위로 살핀다.
        val page = catalog.list(repository.key, cursor = cursor, limit = limit)
        val target by lazy { gateway.snapshot(repository, queryRevision) }
        val targetEntry by lazy { target.entries[path]?.takeIf { it.type == "blob" } }
        val targetBytes by lazy { targetEntry?.let { gateway.blob(repository, it.sha) } }
        val items = page.items.flatMap { summary ->
            val record = facade.get(summary.id)
            val snapshots = mutableMapOf<String, GitEvidenceSnapshot>()
            val ancestry = mutableMapOf<String, Boolean>()
            fun snapshot(ref: String) = snapshots.getOrPut(ref) { gateway.snapshot(repository, ref) }
            // 원문은 현재 후보를 처리하는 동안만 보관한다.
            val blobs = mutableMapOf<String, ByteArray>()
            record.codeAnchors.mapNotNull { anchor ->
                val source = (if (anchor.side == CodeSide.BASE) record.baseRevision else record.targetRevision) ?: return@mapNotNull null
                val samePath = anchor.relativePath == path
                var match = IntentMatch.RELATED_UNVERIFIED
                var range: IntRange? = null
                if (source == queryRevision) {
                    if (!samePath) return@mapNotNull null
                    if (line in anchor.startLine..anchor.endLine) {
                        match = IntentMatch.EXACT_REVISION
                        range = anchor.startLine..anchor.endLine
                    }
                } else {
                    val old = snapshot(source)
                    val entry = old.entries[anchor.relativePath]?.takeIf { it.type == "blob" }
                    val renamed = !samePath && entry != null && entry.sha == targetEntry?.sha &&
                        path !in old.entries && anchor.relativePath !in target.entries &&
                        old.entries.values.count { it.type == "blob" && it.sha == entry.sha } == 1 &&
                        target.entries.values.count { it.type == "blob" && it.sha == entry.sha } == 1
                    if (!samePath && !renamed) return@mapNotNull null
                    if (entry != null && targetEntry != null && ancestry.getOrPut(source) { gateway.isAncestor(repository, source, queryRevision) }) {
                        val oldBytes = blobs.getOrPut(entry.sha) { gateway.blob(repository, entry.sha) }
                        if (GitEvidenceDigest.lines(oldBytes, anchor.startLine, anchor.endLine) == anchor.contentHash) {
                            if (entry.sha == targetEntry?.sha && line in anchor.startLine..anchor.endLine) {
                                range = anchor.startLine..anchor.endLine
                                match = if (renamed) IntentMatch.ANCESTOR_RENAMED_FILE else IntentMatch.ANCESTOR_UNCHANGED_FILE
                            } else if (samePath) {
                                range = targetBytes?.let { LineRelocation.find(oldBytes, it, anchor.startLine, anchor.endLine) }
                                    ?.takeIf { line in it }
                                if (range != null) match = if (range.first == anchor.startLine) IntentMatch.ANCESTOR_UNCHANGED_LINES else IntentMatch.ANCESTOR_MOVED_LINES
                            }
                        }
                    }
                    if (renamed && match == IntentMatch.RELATED_UNVERIFIED) return@mapNotNull null
                }
                HistoricalIntent(summary, source, anchor.side, match,
                    match == IntentMatch.EXACT_REVISION && record.targetRevision == queryRevision,
                    anchor.relativePath, anchor.startLine, anchor.endLine, range?.first, range?.last)
            }
        }
        return ChangeIntentHistory(queryRevision, path, items, page.nextCursor, page.items.size)
    }
}

internal object LineRelocation {
    fun find(source: ByteArray, target: ByteArray, start: Int, end: Int): IntRange? {
        // 1바이트 문자 집합으로 원래 줄 끝과 UTF-8 바이트를 그대로 비교한다.
        val old = source.toString(Charsets.ISO_8859_1)
        val current = target.toString(Charsets.ISO_8859_1)
        val offsets = mutableListOf(0)
        old.forEachIndexed { index, c -> if (c == '\n' && index + 1 < old.length) offsets.add(index + 1) }
        if (start < 1 || end < start || end > offsets.size || old.isEmpty()) return null
        val from = offsets[start - 1]
        val fragment = old.substring(from, offsets.getOrElse(end) { old.length })
        if (fragment.isBlank() || old.indexOf(fragment) != from || old.lastIndexOf(fragment) != from) return null
        val position = current.indexOf(fragment)
        if (position < 0 || current.lastIndexOf(fragment) != position || (position > 0 && current[position - 1] != '\n')) return null
        if (!fragment.endsWith('\n') && position + fragment.length != current.length) return null
        val first = current.take(position).count { it == '\n' } + 1
        return first..(first + end - start)
    }
}
