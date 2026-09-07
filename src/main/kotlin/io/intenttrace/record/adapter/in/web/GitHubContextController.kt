package io.intenttrace.record.adapter.`in`.web

import io.intenttrace.record.application.GitHubContextService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/github")
class GitHubContextController(private val context: GitHubContextService) {
    @GetMapping("/request-context")
    fun request(@RequestParam repositoryKey: String, @RequestParam number: Int) = context.request(repositoryKey, number)

    @GetMapping("/actions")
    fun actions(@RequestParam repositoryKey: String, @RequestParam revision: String,
                @RequestParam(defaultValue = "1") page: Int) = context.actions(repositoryKey, revision, page)
}
