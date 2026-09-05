package io.intenttrace.record.application

import io.intenttrace.identity.application.RepositoryAccessService
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.record.domain.ChangeRecordStatus
import java.util.UUID
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
    val failures: List<HistoryCandidateFailure> = emptyList(),
    val stopReason: HistoryStopReason? = null,
    val complete: Boolean = failures.isEmpty() && stopReason == null,
)

data class HistoryCandidateFailure(val recordId: UUID, val reason: EvidenceUnavailableReason)

@Service
class ChangeIntentHistoryService(
    private val catalog: ChangeRecordCatalogService,
    private val facade: ChangeRecordFacade,
    private val access: RepositoryAccessService,
    private val gateway: GitEvidenceGateway,
    private val readPolicy: HistoryReadPolicy,
) {
    fun find(repositoryKey: String, revision: String, path: String, line: Int, cursor: String? = null, limit: Int = 5, retryRecordId: UUID? = null): ChangeIntentHistory {
        val repository = GitHubRepository.parse(repositoryKey)
        val queryRevision = GitRevision.parse(revision).value
        requireRepositoryRelativePath(path)
        require(line > 0 && limit in 1..20) { "줄은 양수이고 이전 기록 조회 크기는 1~20이어야 합니다." }
        val budget = readPolicy.start()
        access.requireReader(repository.key)
        require(retryRecordId == null || cursor == null) { "실패 기록 재조회와 다음 페이지 커서를 함께 지정할 수 없습니다." }
        // 이름이 바뀐 기록도 찾도록 저장소 후보를 제한된 페이지 단위로 살핀다.
        val queryDigest = HistoryResumeCursor.queryDigest(repository.key, queryRevision, path, line)
        val resume = cursor?.takeIf { it.startsWith("h1.") }?.let { HistoryResumeCursor.parse(it, queryDigest) }
        fun summary(id: UUID): ChangeRecordSummary {
            val record = facade.get(id)
            if (record.repositoryKey != repository.key || record.status !in setOf(ChangeRecordStatus.PUBLISHED, ChangeRecordStatus.SUPERSEDED)) {
                throw ChangeRecordNotFoundException(id)
            }
            return ChangeRecordSummary(record.id, record.title, record.requestSummary, record.repositoryKey,
                record.targetRevision, record.status, record.createdBy, record.createdAt, record.supersededBy, record.version)
        }
        val page = if (resume != null) {
            val current = summary(resume.record.id)
            require(current.createdAt == resume.record.createdAt && resume.anchorIndex < facade.get(current.id).codeAnchors.size) { "재개할 기록과 근거를 확인해 주세요." }
            val tail = if (resume.remainingCandidates > 1) catalog.list(repository.key, cursor = resume.record.encode(), limit = resume.remainingCandidates - 1).items else emptyList()
            val candidates = listOf(current) + tail
            ChangeRecordPage(candidates, if (resume.hasMore) candidates.last().let { RecordCursor(it.createdAt, it.id).encode() } else null)
        } else if (retryRecordId == null) catalog.list(repository.key, cursor = cursor, limit = limit) else ChangeRecordPage(listOf(summary(retryRecordId)), null)
        val reads = GitEvidenceReads(repository, gateway, budget)
        val failures = mutableListOf<HistoryCandidateFailure>()
        val target by lazy { reads.snapshot(queryRevision) }
        val targetEntry by lazy { target.entries[path]?.takeIf { it.type == "blob" } }
        val targetBytes by lazy { targetEntry?.let { reads.blob(it.sha) } }
        val items = mutableListOf<HistoricalIntent>()
        for ((candidateIndex, summary) in page.items.withIndex()) {
            val record = facade.get(summary.id)
            val startAnchor = if (candidateIndex == 0) resume?.anchorIndex ?: 0 else 0
            for (anchorIndex in startAnchor until record.codeAnchors.size) {
                try {
                    budget.checkpoint()
                    val anchor = record.codeAnchors[anchorIndex]
                    val item = run {
                        val source = (if (anchor.side == CodeSide.BASE) record.baseRevision else record.targetRevision) ?: return@run null
                        val samePath = anchor.relativePath == path
                        var match = IntentMatch.RELATED_UNVERIFIED
                        var range: IntRange? = null
                        if (source == queryRevision) {
                            if (!samePath) return@run null
                            if (line in anchor.startLine..anchor.endLine) {
                                match = IntentMatch.EXACT_REVISION
                                range = anchor.startLine..anchor.endLine
                            }
                        } else {
                            val old = reads.snapshot(source)
                            val entry = old.entries[anchor.relativePath]?.takeIf { it.type == "blob" }
                            val renamed = !samePath && entry != null && entry.sha == targetEntry?.sha &&
                                path !in old.entries && anchor.relativePath !in target.entries &&
                                old.entries.values.count { it.type == "blob" && it.sha == entry.sha } == 1 &&
                                target.entries.values.count { it.type == "blob" && it.sha == entry.sha } == 1
                            if (!samePath && !renamed) return@run null
                            if (entry != null && targetEntry != null && reads.isAncestor(source, queryRevision)) {
                                val oldBytes = reads.blob(entry.sha)
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
                            if (renamed && match == IntentMatch.RELATED_UNVERIFIED) return@run null
                        }
                        HistoricalIntent(summary, source, anchor.side, match,
                            match == IntentMatch.EXACT_REVISION && record.targetRevision == queryRevision,
                            anchor.relativePath, anchor.startLine, anchor.endLine, range?.first, range?.last)
                    }
                    budget.checkpoint()
                    item?.let(items::add)
                } catch (failure: EvidenceUnavailableException) {
                    failures += HistoryCandidateFailure(summary.id, failure.reason)
                    break
                } catch (stopped: EvidenceReadStopped) {
                    val next = HistoryResumeCursor(queryDigest, RecordCursor(summary.createdAt, summary.id), anchorIndex,
                        page.items.size - candidateIndex, page.nextCursor != null).encode()
                    return ChangeIntentHistory(queryRevision, path, items, next, candidateIndex + 1, failures, stopped.reason)
                }
            }
        }
        return ChangeIntentHistory(queryRevision, path, items, page.nextCursor, page.items.size, failures)
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
