package io.intenttrace.record.adapter.`in`.web

import io.intenttrace.record.application.RecordActivities
import io.intenttrace.record.application.RecordActivityService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class RecordActivityController(private val activities: RecordActivityService) {
    @GetMapping("/api/v1/change-records/{recordId}/activities")
    fun list(@PathVariable recordId: UUID, @RequestParam(required = false) beforeVersion: Long?): RecordActivities =
        activities.list(recordId, beforeVersion)
}
