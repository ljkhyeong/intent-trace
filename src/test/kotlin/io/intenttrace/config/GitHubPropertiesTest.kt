package io.intenttrace.config

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
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
}
