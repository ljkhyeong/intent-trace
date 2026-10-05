package io.intenttrace.record.application

import io.intenttrace.identity.application.RepositoryAccessService
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.CodeAnchor
import java.util.UUID
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.requireFullRevision
import io.intenttrace.record.domain.TEAM_VISIBLE_STATUSES
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
    val resumeBlocked: Boolean = false,
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
        val queryRevision = requireFullRevision(revision)
        val normalizedPath = requireRepositoryRelativePath(path)
        require(line > 0 && limit in 1..20) { "줄은 양수이고 이전 기록 조회 크기는 1~20이어야 합니다." }
        val budget = readPolicy.start()
        access.requireReader(repository.key)
        require(retryRecordId == null || cursor == null) { "실패 기록 재조회와 다음 페이지 커서를 함께 지정할 수 없습니다." }
        // 이름이 바뀐 기록도 찾도록 저장소 후보를 제한된 페이지 단위로 살핀다.
        val queryDigest = HistoryResumeCursor.queryDigest(repository.key, queryRevision, normalizedPath, line)
        val resume = cursor?.takeIf { it.startsWith("h1.") }?.let { HistoryResumeCursor.parse(it, queryDigest) }
        val candidates = HistoryCandidates(repository.key)
        val page = when {
            resume != null -> candidates.resumePage(resume)
            retryRecordId == null -> catalog.list(repository.key, cursor = cursor, limit = limit)
            else -> ChangeRecordPage(listOf(candidates.summary(retryRecordId)), null)
        }
        val matcher = AnchorMatcher(GitEvidenceReads(repository, gateway, budget), queryRevision, normalizedPath, line)
        val failures = mutableListOf<HistoryCandidateFailure>()
        val items = mutableListOf<HistoricalIntent>()
        for ((candidateIndex, summary) in page.items.withIndex()) {
            val record = candidates.load(summary.id)
            val startAnchor = if (candidateIndex == 0) resume?.anchorIndex ?: 0 else 0
            for (anchorIndex in startAnchor until record.codeAnchors.size) {
                try {
                    budget.checkpoint()
                    val item = matcher.match(summary, record, record.codeAnchors[anchorIndex])
                    budget.checkpoint()
                    item?.let(items::add)
                } catch (failure: EvidenceUnavailableException) {
                    failures += HistoryCandidateFailure(summary.id, failure.reason)
                    break
                } catch (stopped: EvidenceReadStopped) {
                    val next = HistoryResumeCursor(queryDigest, RecordCursor(summary.createdAt, summary.id), anchorIndex,
                        page.items.size - candidateIndex, page.nextCursor != null).encode()
                    return ChangeIntentHistory(queryRevision, normalizedPath, items, next, candidateIndex + 1, failures, stopped.reason,
                        resumeBlocked = candidateIndex == 0 && anchorIndex == startAnchor && stopped.reason != HistoryStopReason.CANCELLED)
                }
            }
        }
        return ChangeIntentHistory(queryRevision, normalizedPath, items, page.nextCursor, page.items.size, failures)
    }

    /** 한 번의 조회에서 같은 기록을 다시 읽지 않고, 재개 커서의 후보 페이지를 다시 만든다. */
    private inner class HistoryCandidates(private val repositoryKey: String) {
        private val loaded = mutableMapOf<UUID, ChangeRecord>()

        fun load(id: UUID): ChangeRecord = loaded.getOrPut(id) { facade.get(id) }

        fun summary(id: UUID): ChangeRecordSummary {
            val record = load(id)
            if (record.repositoryKey != repositoryKey || record.status !in TEAM_VISIBLE_STATUSES) {
                throw ChangeRecordNotFoundException(id)
            }
            return record.toSummary()
        }

        fun resumePage(resume: HistoryResumeCursor): ChangeRecordPage {
            val current = summary(resume.record.id)
            require(current.createdAt == resume.record.createdAt && resume.anchorIndex < load(current.id).codeAnchors.size) { "재개할 기록과 근거를 확인해 주세요." }
            val tail = if (resume.remainingCandidates > 1) catalog.list(repositoryKey, cursor = resume.record.encode(), limit = resume.remainingCandidates - 1).items else emptyList()
            val candidates = listOf(current) + tail
            return ChangeRecordPage(candidates, if (resume.hasMore) candidates.last().let { RecordCursor(it.createdAt, it.id).encode() } else null)
        }
    }
}

/** 기록의 코드 근거가 조회한 커밋·파일·줄과 어떻게 이어지는지 판정한다. 조회 커밋의 원격 자료는 처음 필요할 때 읽는다. */
private class AnchorMatcher(
    private val reads: GitEvidenceReads,
    private val queryRevision: String,
    private val path: String,
    private val line: Int,
) {
    private val target by lazy { reads.snapshot(queryRevision) }
    private val targetEntry by lazy { target.entries[path]?.takeIf { it.type == "blob" } }
    private val targetBytes by lazy { targetEntry?.let { reads.blob(it.sha) } }

    fun match(summary: ChangeRecordSummary, record: ChangeRecord, anchor: CodeAnchor): HistoricalIntent? {
        val source = (if (anchor.side == CodeSide.BASE) record.baseRevision else record.targetRevision) ?: return null
        val (match, range) = (if (source == queryRevision) sameRevision(anchor) else ancestor(source, anchor)) ?: return null
        return HistoricalIntent(summary, source, anchor.side, match,
            match == IntentMatch.EXACT_REVISION && record.targetRevision == queryRevision,
            anchor.relativePath, anchor.startLine, anchor.endLine, range?.first, range?.last)
    }

    private fun sameRevision(anchor: CodeAnchor): Pair<IntentMatch, IntRange?>? {
        if (anchor.relativePath != path) return null
        return if (line in anchor.startLine..anchor.endLine) IntentMatch.EXACT_REVISION to anchor.startLine..anchor.endLine
            else IntentMatch.RELATED_UNVERIFIED to null
    }

    private fun ancestor(source: String, anchor: CodeAnchor): Pair<IntentMatch, IntRange?>? {
        val samePath = anchor.relativePath == path
        val old = reads.snapshot(source)
        val entry = old.entries[anchor.relativePath]?.takeIf { it.type == "blob" }
        val renamed = !samePath && entry != null && entry.sha == targetEntry?.sha &&
            path !in old.entries && anchor.relativePath !in target.entries &&
            old.entries.values.singleOrNull { it.type == "blob" && it.sha == entry.sha } != null &&
            target.entries.values.singleOrNull { it.type == "blob" && it.sha == entry.sha } != null
        if (!samePath && !renamed) return null
        var match = IntentMatch.RELATED_UNVERIFIED
        var range: IntRange? = null
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
        if (renamed && match == IntentMatch.RELATED_UNVERIFIED) return null
        return match to range
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
        if (fragment.isBlank()) return null
        fun uniqueLinePosition(text: String): Int? =
            generateSequence(text.indexOf(fragment).takeIf { it >= 0 }) { position ->
                // 한 글자 뒤부터 찾아 서로 겹치는 여러 줄 조각도 중복으로 센다.
                text.indexOf(fragment, position + 1).takeIf { it >= 0 }
            }.filter { position ->
                (position == 0 || text[position - 1] == '\n') &&
                    (fragment.endsWith('\n') || position + fragment.length == text.length)
            }.take(2).singleOrNull()
        if (uniqueLinePosition(old) != from) return null
        val position = uniqueLinePosition(current) ?: return null
        val first = current.take(position).count { it == '\n' } + 1
        return first..(first + end - start)
    }
}
