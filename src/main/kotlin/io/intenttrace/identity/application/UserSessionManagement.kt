package io.intenttrace.identity.application

import io.intenttrace.identity.domain.ActorIdentity
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class UserSessionInfo(
    val id: UUID,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant,
    val current: Boolean = false,
    val channel: SessionChannel,
    val expiresAt: Instant,
)

data class MySessions(val actor: ActorIdentity, val sessions: List<UserSessionInfo>)
data class SessionRevocation(val revokedCount: Int)

interface UserSessionManagement {
    fun list(subject: String): List<UserSessionInfo>
    fun revoke(subject: String, sessionId: UUID): Boolean
    fun revokeAll(subject: String): Int
}

@Service
class MySessionService(private val current: CurrentGitHubUserSession, private val sessions: UserSessionManagement) {
    fun list(): MySessions {
        val session = current.require()
        return MySessions(session.actor, sessions.list(session.actor.subject).map { it.copy(current = it.id == session.sessionId) })
    }

    fun revoke(sessionId: UUID? = null): SessionRevocation {
        val session = current.require()
        return SessionRevocation(if (sessions.revoke(session.actor.subject, sessionId ?: session.sessionId)) 1 else 0)
    }

    fun revokeAll(): SessionRevocation = SessionRevocation(sessions.revokeAll(current.require().actor.subject))
}
