package io.intenttrace.intellij

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                "createdBy": {"id": 1, "login": "developer"},
                "decisions": [
                  {"summary": "PasswordSafe를 사용한다.", "rationale": "평문 저장을 피한다.", "source": "CONFIRMED_AI_SUMMARY"}
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
        assertEquals("CONFIRMED_AI_SUMMARY", records.single().decisions.single().source)
        assertTrue(records.single().verifications.single().current)
    }

    @Test
    fun `형식이 잘못된 응답은 사용자가 이해할 수 있는 오류로 바꾼다`() {
        val exception = assertFailsWith<IntentTraceClientException> {
            IntentTraceResponseParser.parse("{\"id\":\"record-1\"}")
        }

        assertEquals("IntentTrace 조회 응답 형식을 확인할 수 없습니다.", exception.message)
    }
}
