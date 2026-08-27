package io.intenttrace.record.adapter.`in`.web

import io.intenttrace.record.application.ChangeRecordFacade
import io.intenttrace.record.application.ChangeRecordMarkdownRenderer
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
    private val facade: ChangeRecordFacade,
    private val markdownRenderer: ChangeRecordMarkdownRenderer,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateChangeRecordRequest): ChangeRecordResponse =
        ChangeRecordResponse.from(facade.create(request.toCommand()))

    @GetMapping("/{recordId}")
    fun get(@PathVariable recordId: UUID): ChangeRecordResponse =
        ChangeRecordResponse.from(facade.get(recordId))

    @PostMapping("/{recordId}/confirm")
    fun confirm(
        @PathVariable recordId: UUID,
        @Valid @RequestBody request: ConfirmChangeRecordRequest,
    ): ChangeRecordResponse = ChangeRecordResponse.from(facade.confirm(request.toCommand(recordId)))

    @PostMapping("/{recordId}/publish")
    fun publish(
        @PathVariable recordId: UUID,
        @Valid @RequestBody request: PublishChangeRecordRequest,
    ): ChangeRecordResponse = ChangeRecordResponse.from(facade.publish(request.toCommand(recordId)))

    @PostMapping("/{recordId}/supersede")
    fun supersede(
        @PathVariable recordId: UUID,
        @Valid @RequestBody request: SupersedeChangeRecordRequest,
    ): ChangeRecordResponse = ChangeRecordResponse.from(facade.supersede(request.toCommand(recordId)))

    @GetMapping("/lookup")
    fun lookup(
        @RequestParam @NotBlank @Size(max = 255) repositoryKey: String,
        @RequestParam @Pattern(regexp = "^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$") revision: String,
        @RequestParam @NotBlank @Size(max = 1000) path: String,
        @RequestParam @Min(1) line: Int,
    ): List<ChangeRecordResponse> = facade.findIntent(repositoryKey, revision, path, line)
        .map(ChangeRecordResponse::from)

    @GetMapping("/{recordId}/markdown", produces = [MediaType.TEXT_MARKDOWN_VALUE])
    fun markdown(@PathVariable recordId: UUID): String = markdownRenderer.render(facade.get(recordId))
}
