package io.intenttrace.intellij

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IntentTraceServerTest {
    @Test
    fun `설정이 없으면 loopback 기본 주소를 사용한다`() {
        assertEquals("http://127.0.0.1:8080", IntentTraceServer.parse(null).baseUri.toString())
    }

    @Test
    fun `HTTPS 팀 서버와 loopback HTTP를 허용한다`() {
        assertEquals("https://trace.example.com", IntentTraceServer.parse("https://trace.example.com/").baseUri.toString())
        assertEquals("http://localhost:8080", IntentTraceServer.parse("http://localhost:8080").baseUri.toString())
    }

    @Test
    fun `외부 HTTP와 하위 경로가 있는 주소는 거부한다`() {
        assertFailsWith<IntentTraceUsageException> {
            IntentTraceServer.parse("http://trace.example.com")
        }
        assertFailsWith<IntentTraceUsageException> {
            IntentTraceServer.parse("https://trace.example.com/service")
        }
        assertFailsWith<IntentTraceUsageException> {
            IntentTraceServer.parse("https:trace.example.com")
        }
    }

    @Test
    fun `현재 줄 조회 query를 URL 인코딩한다`() {
        val uri = IntentTraceServer.parse("https://trace.example.com").lookupUri(
            LineLookup(
                repositoryKey = "team/repository",
                revision = "a".repeat(40),
                relativePath = "src/main/한글 파일.kt",
                line = 12,
            ),
        )

        assertEquals(
            "https://trace.example.com/api/v1/change-records/lookup" +
                "?repositoryKey=team%2Frepository&revision=${"a".repeat(40)}" +
                "&path=src%2Fmain%2F%ED%95%9C%EA%B8%80+%ED%8C%8C%EC%9D%BC.kt&line=12",
            uri.toString(),
        )
    }
}
