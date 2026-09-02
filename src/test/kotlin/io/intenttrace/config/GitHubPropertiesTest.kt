package io.intenttrace.config

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubPropertiesTest {
    @Test
    fun `GitHub 설정 문자열은 비밀값을 노출하지 않는다`() {
        val properties = GitHubProperties(
            token = "fixed-token-secret",
            app = GitHubAppProperties(
                clientId = "Iv1.example",
                privateKeyBase64 = "private-key-secret",
            ),
            userAuthorization = GitHubUserAuthorizationProperties(
                clientSecret = "client-secret-value",
            ),
        )

        val text = properties.toString()

        assertFalse(text.contains("fixed-token-secret"))
        assertFalse(text.contains("private-key-secret"))
        assertFalse(text.contains("client-secret-value"))
        assertTrue(text.contains("[보호됨]"))
    }

    @Test
    fun `OAuth 대기 state 상한은 1 이상이어야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            GitHubUserAuthorizationProperties(maxPendingStates = 0)
        }
    }

    @Test
    fun `사용자별 session 상한은 1 이상이어야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            GitHubUserAuthorizationProperties(maxSessionsPerUser = 0)
        }
    }

    @Test
    fun `GitHub API 버전은 실제 날짜여야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            GitHubProperties(apiVersion = "2026-99-99")
        }
    }

    @Test
    fun `callback은 IPv6 loopback HTTP만 허용한다`() {
        for (host in listOf("[::1]", "[0:0:0:0:0:0:0:1]")) {
            val callback = URI.create("http://$host:8080/auth/github/callback")
            assertEquals(callback, GitHubUserAuthorizationProperties(callbackUrl = callback).callbackUrl)
        }
        for (host in listOf("[::]", "[2001:db8::1]")) {
            assertFailsWith<IllegalArgumentException> {
                GitHubUserAuthorizationProperties(callbackUrl = URI.create("http://$host:8080/auth/github/callback"))
            }
        }
    }
}
