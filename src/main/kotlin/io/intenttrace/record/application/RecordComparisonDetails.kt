package io.intenttrace.record.application

import io.intenttrace.record.domain.ChangeRecord

enum class ItemChange { ADDED, REMOVED, MODIFIED, MOVED, AMBIGUOUS }
data class ComparisonDetail(
    val field: ComparisonField,
    val change: ItemChange,
    val originalIndex: Int?,
    val successorIndex: Int?,
    val changedProperties: List<String> = emptyList(),
    val moved: Boolean = false,
)

internal fun comparisonDetails(original: ChangeRecord, successor: ChangeRecord): List<ComparisonDetail> =
    compareItems(ComparisonField.DECISIONS, original.decisions, successor.decisions, { it.summary },
        { mapOf("summary" to it.summary, "rationale" to it.rationale, "source" to it.source) }) +
    compareItems(ComparisonField.CODE_ANCHORS, original.codeAnchors, successor.codeAnchors,
        { listOf(it.side, it.relativePath, it.startLine, it.endLine) },
        { mapOf("symbolName" to it.symbolName, "contentHash" to it.contentHash, "relatedPath" to it.relatedPath) }) +
    compareItems(ComparisonField.VERIFICATIONS, original.verifications, successor.verifications,
        { listOf(it.command, it.startedAt, it.finishedAt) },
        { mapOf("exitCode" to it.exitCode, "snapshotDigest" to it.snapshotDigest, "outputDigest" to it.outputDigest, "summary" to it.summary, "source" to it.source) }) +
    compareItems(ComparisonField.OPEN_QUESTIONS, original.openQuestions, successor.openQuestions, { it }, { mapOf("text" to it) })

private fun <T, K> compareItems(field: ComparisonField, before: List<T>, after: List<T>, key: (T) -> K,
    properties: (T) -> Map<String, Any?>): List<ComparisonDetail> {
    if (before == after) return emptyList()
    val oldKeys = before.map(key)
    val newKeys = after.map(key)
    // 중복 항목을 임의로 대응시키면 삭제·출처 변경을 잘못 표시할 수 있다.
    if (oldKeys.distinct().size != oldKeys.size || newKeys.distinct().size != newKeys.size) {
        return listOf(ComparisonDetail(field, ItemChange.AMBIGUOUS, null, null))
    }
    val oldIndex = oldKeys.withIndex().associate { it.value to it.index }
    val newIndex = newKeys.withIndex().associate { it.value to it.index }
    val oldCommon = oldKeys.filter { it in newIndex }.withIndex().associate { it.value to it.index }
    val newCommon = newKeys.filter { it in oldIndex }.withIndex().associate { it.value to it.index }
    return buildList {
        oldKeys.forEachIndexed { index, value ->
            val next = newIndex[value]
            if (next == null) add(ComparisonDetail(field, ItemChange.REMOVED, index, null))
            else {
                val left = properties(before[index]); val right = properties(after[next])
                val changed = left.keys.filter { left[it] != right[it] }
                val moved = oldCommon[value] != newCommon[value]
                if (changed.isNotEmpty() || moved) add(ComparisonDetail(field,
                    if (changed.isNotEmpty()) ItemChange.MODIFIED else ItemChange.MOVED, index, next, changed, moved))
            }
        }
        newKeys.forEachIndexed { index, value -> if (value !in oldIndex) add(ComparisonDetail(field, ItemChange.ADDED, null, index)) }
    }
}
