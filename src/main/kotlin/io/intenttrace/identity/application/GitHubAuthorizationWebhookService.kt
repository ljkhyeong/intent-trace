package io.intenttrace.identity.application

import io.intenttrace.identity.domain.ActorIdentity
import org.springframework.stereotype.Service

@Service
class GitHubAuthorizationWebhookService(private val sessions: UserSessionManagement) {
    fun revoked(githubUserId: Long) {
        sessions.revokeAll(ActorIdentity.githubSubject(githubUserId))
    }
}
