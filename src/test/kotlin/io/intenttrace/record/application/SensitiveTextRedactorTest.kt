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
            session=its_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            browser=itb_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB
            /Users/lim/devProject/intent-trace C:\Users\lim\intent-trace
        """.trimIndent()

        val redacted = redactor.redact(source)

        assertEquals(6, Regex(Regex.escape("[REDACTED]")).findAll(redacted).count())
        assertFalse(redacted.contains("ghu_userToken123"))
        assertFalse(redacted.contains("itb_" + "B".repeat(43)))
        assertFalse(redacted.contains("lim"))
    }

    @Test
    fun `서버와 실행 도구가 점으로 구분된 설치 token 전체를 제거하고 문장 끝을 보존한다`() {
        val samples = listOf("ghs_" + "a".repeat(36) + "." + "b".repeat(36) + "." + "c".repeat(35) + "-", "ghs_classicToken", "its_" + "a".repeat(42) + "-")
        val script = java.nio.file.Path.of("scripts/run-verification.py").toAbsolutePath().toString()
        for (sample in samples) {
            val source = "설명 ($sample). 다음 문장"
            val expected = "설명 ([REDACTED]). 다음 문장"
            assertEquals(expected, redactor.redact(source))
            val process = ProcessBuilder("python3", "-c",
                "import runpy,sys; print(runpy.run_path(sys.argv[1])['redact'](sys.argv[2]))", script, source).start()
            assertEquals(expected, process.inputStream.bufferedReader().readText().trim())
            assertEquals(0, process.waitFor())
        }
    }

    @Test
    fun `이스케이프된 따옴표와 역슬래시를 포함한 비밀값 전체를 제거한다`() {
        val sources = listOf(
            """{"password": "prefix\"TAIL_ONLY_FOR_TEST", "label": "남길 값"}""",
            """{"password": "prefix\\\"TAIL_ONLY_FOR_TEST", "label": "남길 값"}""",
            """{"password": "prefix\\", "label": "남길 값"}""",
            """{"password": "${"a".repeat(1900)}\"TAIL_ONLY_FOR_TEST", "label": "남길 값"}""",
        )
        sources.forEach { source ->
            assertEquals("""{"password": [REDACTED], "label": "남길 값"}""", redactor.redact(source))
        }
        assertEquals(
            "secret=[REDACTED]; label=남길값",
            redactor.redact("""secret='prefix\'TAIL_ONLY_FOR_TEST'; label=남길값"""),
        )
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

    @Test
    fun `Base64 URL 문자를 포함한 IntentTrace 세션을 제거한다`() {
        val tokens = listOf(
            "its_-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "its_AAAAAAAAAAAAAAAAAAAAA-AAAAAAAAAAAAAAAAAAAAA",
            "its_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA-",
        )

        tokens.forEach { token ->
            assertEquals("세션 [REDACTED] 사용", redactor.redact("세션 $token 사용"))
        }
    }

    @Test
    fun `독립된 compact JWT를 제거한다`() {
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJleGFtcGxlIn0.signatureValue123"

        assertEquals("jwt=[REDACTED]", redactor.redact("jwt=$jwt"))
    }
}
