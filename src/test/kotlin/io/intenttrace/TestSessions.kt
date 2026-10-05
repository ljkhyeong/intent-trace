package io.intenttrace

import io.intenttrace.identity.application.GitHubUserOAuthTokens
import io.intenttrace.identity.application.GitHubUserSessionStore
import io.intenttrace.identity.application.SessionChannel
import io.intenttrace.identity.domain.ActorIdentity
import java.time.Instant

/** REST·MCP 테스트용 its_ 세션을 발급한다. GitHub 토큰 쌍은 [accessToken]에서 만든다. */
fun GitHubUserSessionStore.issueTestSession(
    actor: ActorIdentity,
    accessToken: String = "ghu_test-session",
    channel: SessionChannel = SessionChannel.CLIENT,
    now: Instant = Instant.now(),
): String = issue(actor, GitHubUserOAuthTokens(accessToken, now.plusSeconds(3600), accessToken.replace("ghu_", "ghr_"), now.plusSeconds(7200)),
    channel).sessionToken
