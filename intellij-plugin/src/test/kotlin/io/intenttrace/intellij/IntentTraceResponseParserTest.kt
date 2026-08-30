package io.intenttrace.intellij

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntentTraceResponseParserTest {
    @Test
    fun `공개 변경 의도 응답을 화면 모델로 변환한다`() {
        val records = IntentTraceResponseParser.parse(
            """
            [
              {
                "id": "record-1",
                "title": "세션 토큰 저장",
                "requestSummary": "IDE에서 현재 줄의 변경 의도를 확인한다.",
                "status": "PUBLISHED",
                "snapshotDigest": "서버에만 필요한 필드",
                "createdBy": {"id": 1, "login": "developer"},
                "decisions": [
                  {"summary": "PasswordSafe를 사용한다.", "rationale": "평문 저장을 피한다.", "source": "CONFIRMED_AI_SUMMARY"},
                  {"summary": "근거가 생략됐다.", "source": "UNKNOWN"},
                  {"summary": "근거가 없다.", "rationale": null, "source": "UNKNOWN"}
                ],
                "codeAnchors": [
                  {"relativePath": "src/main/App.kt", "startLine": 10, "endLine": 12}
                ],
                "verifications": [
                  {"command": "./gradlew test", "exitCode": 0, "summary": "테스트 통과", "current": true}
                ],
                "openQuestions": ["Marketplace 배포 시점을 정한다."]
              }
            ]
            """.trimIndent(),
        )

        assertEquals(1, records.size)
        assertEquals("developer", records.single().authorLogin)
        assertEquals("CONFIRMED_AI_SUMMARY", records.single().decisions.first().source)
        assertNull(records.single().decisions[1].rationale)
        assertNull(records.single().decisions[2].rationale)
        assertTrue(records.single().verifications.single().current)
    }

    @Test
    fun `문자열 필드에 숫자가 오면 응답 형식 오류로 처리한다`() {
        assertFailsWith<IntentTraceClientException> {
            IntentTraceResponseParser.parse(
                """
                [{
                  "id": "record-1", "title": 123, "requestSummary": "요청", "status": "PUBLISHED",
                  "createdBy": {"login": "developer"},
                  "decisions": [], "codeAnchors": [], "verifications": [], "openQuestions": []
                }]
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `형식이 잘못된 응답은 원문을 남기지 않는 안내 오류로 바꾼다`() {
        val marker = "test-private-response-marker"
        val exception = assertFailsWith<IntentTraceClientException> {
            IntentTraceResponseParser.parse("""{"id":"$marker"}""")
        }

        assertEquals("IntentTrace 조회 응답 형식을 확인할 수 없습니다.", exception.message)
        assertFalse(exception.stackTraceToString().contains(marker))
    }
}
