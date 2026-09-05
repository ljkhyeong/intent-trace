package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.identity.application.GitHubUserSessionService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class GitHubUserSessionController(
    private val sessions: GitHubUserSessionService,
) {
    @DeleteMapping("/api/v1/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke() {
        sessions.revokeCurrent()
    }
}
