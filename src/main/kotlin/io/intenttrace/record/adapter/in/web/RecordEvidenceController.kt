package io.intenttrace.record.adapter.`in`.web

import io.intenttrace.record.application.ChangeIntentHistory
import io.intenttrace.record.application.ChangeIntentHistoryService
import io.intenttrace.record.application.RecordEvidenceCheck
import io.intenttrace.record.application.RecordEvidenceService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/change-records")
class RecordEvidenceController(private val evidence: RecordEvidenceService, private val history: ChangeIntentHistoryService) {
    @GetMapping("/{recordId}/evidence-check")
    fun check(@PathVariable recordId: UUID): RecordEvidenceCheck = evidence.check(recordId)

    @GetMapping("/history")
    fun history(
        @RequestParam repositoryKey: String, @RequestParam revision: String,
        @RequestParam path: String, @RequestParam line: Int,
        @RequestParam(required = false) cursor: String?, @RequestParam(defaultValue = "5") limit: Int,
    ): ChangeIntentHistory = history.find(repositoryKey, revision, path, line, cursor, limit)
}
