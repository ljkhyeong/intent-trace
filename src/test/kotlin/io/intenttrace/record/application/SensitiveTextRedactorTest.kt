package io.intenttrace.record.application

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SensitiveTextRedactorTest {
    private val redactor = SensitiveTextRedactor()

    @Test
    fun `token과 개인 경로를 기록 전에 제거한다`() {
        val source = """
            Authorization: Bearer ghu_userToken123
            {"client_secret": "공백이 있는 비밀값"}
            session=its_sessionToken123
            /Users/lim/devProject/intent-trace C:\Users\lim\intent-trace
        """.trimIndent()

        val redacted = redactor.redact(source)

        assertEquals(5, Regex(Regex.escape("[REDACTED]")).findAll(redacted).count())
        assertFalse(redacted.contains("ghu_userToken123"))
        assertFalse(redacted.contains("lim"))
    }

    @Test
    fun `PEM private key 본문 전체를 제거한다`() {
        val source = """
            -----BEGIN PRIVATE KEY-----
            private-key-body
            -----END PRIVATE KEY-----
        """.trimIndent()

        assertEquals("[REDACTED]", redactor.redact(source))
    }
}
