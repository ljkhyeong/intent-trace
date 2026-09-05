package io.intenttrace.record.adapter.`in`.web

import io.intenttrace.record.application.ChangeRecordMarkdownRenderer
import io.intenttrace.record.application.TeamChangeRecordService
import io.intenttrace.record.application.ChangeRecordCatalogService
import io.intenttrace.record.application.ChangeRecordPage
import io.intenttrace.record.application.RecordScope
import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.FULL_GIT_REVISION_PATTERN
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/change-records")
class ChangeRecordController(
    private val records: TeamChangeRecordService,
    private val markdownRenderer: ChangeRecordMarkdownRenderer,
    private val catalog: ChangeRecordCatalogService,
) {
    @GetMapping
    fun list(
        @RequestParam repositoryKey: String,
        @RequestParam(defaultValue = "TEAM") scope: RecordScope,
        @RequestParam(required = false) path: String?,
        @RequestParam(required = false) status: ChangeRecordStatus?,
        @RequestParam(required = false) authorId: Long?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ChangeRecordPage = catalog.list(repositoryKey, scope, path, status, authorId, cursor, limit)

    @PostMapping("/{recordId}/revise")
    fun revise(@PathVariable recordId: UUID, @Valid @RequestBody request: ReviseChangeRecordRequest): ChangeRecordResponse =
        ChangeRecordResponse.from(records.revise(recordId, request.expectedVersion, request.content.toCommand()))

    @PostMapping("/{recordId}/reopen")
    fun reopen(@PathVariable recordId: UUID, @Valid @RequestBody request: RecordVersionRequest): ChangeRecordResponse =
        ChangeRecordResponse.from(records.reopen(recordId, request.expectedVersion))

    @PostMapping("/{recordId}/discard")
    fun discard(@PathVariable recordId: UUID, @Valid @RequestBody request: RecordVersionRequest): ChangeRecordResponse =
        ChangeRecordResponse.from(records.discard(recordId, request.expectedVersion))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateChangeRecordRequest): ChangeRecordResponse =
        ChangeRecordResponse.from(records.create(request.toCommand()))

    @GetMapping("/{recordId}")
    fun get(@PathVariable recordId: UUID): ChangeRecordResponse =
        ChangeRecordResponse.from(records.get(recordId))

    @PostMapping("/{recordId}/confirm")
    fun confirm(
        @PathVariable recordId: UUID,
        @Valid @RequestBody request: ConfirmChangeRecordRequest,
    ): ChangeRecordResponse = ChangeRecordResponse.from(records.confirm(request.toCommand(recordId)))

    @PostMapping("/{recordId}/publish")
    fun publish(
        @PathVariable recordId: UUID,
        @Valid @RequestBody request: PublishChangeRecordRequest,
    ): ChangeRecordResponse = ChangeRecordResponse.from(records.publish(request.toCommand(recordId)))

    @PostMapping("/{recordId}/supersede")
    fun supersede(
        @PathVariable recordId: UUID,
        @Valid @RequestBody request: SupersedeChangeRecordRequest,
    ): ChangeRecordResponse = ChangeRecordResponse.from(records.supersede(request.toCommand(recordId)))

    @GetMapping("/lookup")
    fun lookup(
        @RequestParam @NotBlank @Size(max = 255) repositoryKey: String,
        @RequestParam @Pattern(regexp = FULL_GIT_REVISION_PATTERN) revision: String,
        @RequestParam @NotBlank @Size(max = 1000) path: String,
        @RequestParam @Min(1) line: Int,
    ): List<ChangeRecordResponse> = records.findIntent(repositoryKey, revision, path, line)
        .map(ChangeRecordResponse::from)

    @GetMapping("/{recordId}/markdown", produces = [MediaType.TEXT_MARKDOWN_VALUE])
    fun markdown(@PathVariable recordId: UUID): String = markdownRenderer.render(records.get(recordId))
}
