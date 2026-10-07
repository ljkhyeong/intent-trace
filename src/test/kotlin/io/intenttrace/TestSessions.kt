package io.intenttrace

import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserAccessGateway
import io.intenttrace.identity.application.GitHubUserOAuthTokens
import io.intenttrace.identity.application.GitHubUserSession
import io.intenttrace.identity.application.GitHubUserSessionStore
import io.intenttrace.identity.application.SessionChannel
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** REST·MCP 테스트용 its_ 세션을 발급한다. GitHub 토큰 쌍은 [accessToken]에서 만든다. */
fun GitHubUserSessionStore.issueTestSession(
    actor: ActorIdentity,
    accessToken: String = "ghu_test-session",
    channel: SessionChannel = SessionChannel.CLIENT,
    now: Instant = Instant.now(),
): String = issue(actor, GitHubUserOAuthTokens(accessToken, now.plusSeconds(3600), accessToken.replace("ghu_", "ghr_"), now.plusSeconds(7200)),
    channel).sessionToken

/**
 * GitHub 사용자 조회 대역. [actors]에 없는 토큰은 [defaultActor]로 인증하고 모든 저장소에 [role]을 돌려준다.
 * [authenticationFailure]를 지정하면 인증 호출마다 그 예외를 던진다.
 */
class TestGitHubUserAccessGateway(
    var role: RepositoryRole? = RepositoryRole.MAINTAINER,
    private val actors: Map<String, ActorIdentity> = emptyMap(),
    private val defaultActor: ActorIdentity = ActorIdentity.github(42, "lim"),
) : GitHubUserAccessGateway {
    var authenticationFailure: RuntimeException? = null
    val authentications = AtomicInteger()
    val roleChecks = AtomicInteger()

    override fun authenticate(accessToken: String): ActorIdentity {
        authentications.incrementAndGet()
        authenticationFailure?.let { throw it }
        return actors[accessToken] ?: defaultActor
    }

    override fun repositoryRole(accessToken: String, actor: ActorIdentity, repository: GitHubRepository): RepositoryRole? {
        roleChecks.incrementAndGet()
        return role
    }
}

/** 요청마다 [actor]로 새 GitHub 사용자 세션을 만든다. 테스트 중 [actor]를 바꿔 다른 사용자 요청을 흉내 낸다. */
class TestCurrentGitHubUserSession(var actor: ActorIdentity, private val accessToken: String = "ghu_test") :
    CurrentGitHubUserSession {
    override fun require() = GitHubUserSession(actor, accessToken, UUID.randomUUID())
}
