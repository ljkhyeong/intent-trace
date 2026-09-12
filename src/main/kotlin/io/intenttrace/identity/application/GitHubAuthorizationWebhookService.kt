package io.intenttrace.identity.application

import org.springframework.stereotype.Service

@Service
class GitHubAuthorizationWebhookService(private val sessions: UserSessionManagement) {
    fun revoked(githubUserId: Long) {
        sessions.revokeAll("github:$githubUserId")
    }
}
