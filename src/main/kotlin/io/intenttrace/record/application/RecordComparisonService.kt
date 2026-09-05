package io.intenttrace.record.application

import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.ChangeRecordContent
import io.intenttrace.record.domain.ChangeRecordStatus
import org.springframework.stereotype.Service
import java.util.UUID

enum class ComparisonField { TITLE, REQUEST, DECISIONS, CODE_ANCHORS, VERIFICATIONS, OPEN_QUESTIONS, BASE_REVISION, TARGET_REVISION, SNAPSHOT }
data class RecordComparisonSide(val id: UUID, val version: Long, val status: ChangeRecordStatus, val targetRevision: String?, val content: ChangeRecordContent)
data class ChangeRecordComparison(val original: RecordComparisonSide, val successor: RecordComparisonSide, val changedFields: List<ComparisonField>,
    val details: List<ComparisonDetail> = emptyList())

@Service
class RecordComparisonService(private val records: TeamChangeRecordService) {
    fun compare(successorId: UUID): ChangeRecordComparison {
        val successor = records.get(successorId)
        val original = records.get(requireNotNull(successor.derivedFromRecordId) { "원본이 연결된 후속 기록만 비교할 수 있습니다." })
        val fields = listOf(
            ComparisonField.TITLE to (original.title != successor.title),
            ComparisonField.REQUEST to (original.requestSummary != successor.requestSummary),
            ComparisonField.DECISIONS to (original.decisions != successor.decisions),
            ComparisonField.CODE_ANCHORS to (original.codeAnchors != successor.codeAnchors),
            ComparisonField.VERIFICATIONS to (original.verifications != successor.verifications),
            ComparisonField.OPEN_QUESTIONS to (original.openQuestions != successor.openQuestions),
            ComparisonField.BASE_REVISION to (original.baseRevision != successor.baseRevision),
            ComparisonField.TARGET_REVISION to (original.targetRevision != successor.targetRevision),
            ComparisonField.SNAPSHOT to (original.snapshotDigest != successor.snapshotDigest),
        ).filter { it.second }.map { it.first }
        return ChangeRecordComparison(original.side(), successor.side(), fields, comparisonDetails(original, successor))
    }

    private fun ChangeRecord.side() = RecordComparisonSide(id, version, status, targetRevision, content())
}
