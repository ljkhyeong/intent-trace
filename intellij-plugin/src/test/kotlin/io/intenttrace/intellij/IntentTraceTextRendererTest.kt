package io.intenttrace.intellij

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class IntentTraceTextRendererTest {
    @Test
    fun `근거 출처와 snapshot 상태를 구분해서 표시한다`() {
        val output = IntentTraceTextRenderer.render(
            lookup = LineLookup("team/repository", "a".repeat(40), "src/main/App.kt", 12),
            records = listOf(record),
        )

        assertContains(output, "[정황에서 추론] 얇은 IDE client를 둔다.")
        assertContains(output, "[기록 스냅샷과 불일치, 종료 코드 0] ./gradlew test")
        assertContains(output, "남은 질문\n- 없음")
    }

    @Test
    fun `변경 전 줄의 검증 결과는 스냅샷 불일치로 단정하지 않는다`() {
        val lookup = LineLookup(record.repositoryKey, "b".repeat(40), "src/main/App.kt", 12)
        for (current in listOf(false, true)) {
            val response = record.copy(baseRevision = lookup.revision,
                verifications = record.verifications.map { it.copy(current = current) })
            val output = IntentTraceTextRenderer.render(lookup, listOf(response))
            assertContains(output, "[다른 커밋의 결과, 종료 코드 0]")
            assertFalse(output.contains("기록 스냅샷과 불일치"))
            assertFalse(output.contains("기록 스냅샷과 일치"))

            val detail = IntentTraceTextRenderer.renderHistory(response)
            assertContains(detail, if (current) "기록 스냅샷과 일치" else "기록 스냅샷과 불일치")
            assertFalse(detail.contains("다른 커밋의 결과"))
        }
    }

    @Test
    fun `검증 수집 출처와 미확인을 구분하며 서버의 실행 확인으로 표시하지 않는다`() {
        for ((source, label) in listOf(
            "LOCAL_RUNNER_REPORTED" to "로컬 실행 도구에서 수집한 결과",
            "CLIENT_REPORTED" to "클라이언트가 제출한 결과",
            null to "미확인",
            "FUTURE_SOURCE" to "미확인",
        )) {
            val response = record.copy(verifications = record.verifications.map { it.copy(source = source) })
            val output = IntentTraceTextRenderer.renderHistory(response)
            assertContains(output, "출처: $label")
            assertContains(output, "서버는 테스트 실행 여부를 확인하지 않습니다.")
        }
    }

    private val record = ChangeIntentRecord(
        id = "record-1", title = "현재 줄 변경 의도", requestSummary = "팀원이 변경 이유를 확인한다.",
        status = "PUBLISHED", authorLogin = "developer",
        decisions = listOf(ChangeDecision("얇은 IDE client를 둔다.", null, "INFERRED")),
        codeAnchors = listOf(ChangeCodeAnchor("src/main/App.kt", 10, 15)),
        verifications = listOf(ChangeVerification("./gradlew test", 0, "통과", false)),
        openQuestions = emptyList(), repositoryKey = "team/repository",
        targetRevision = "a".repeat(40), supersededBy = null,
    )
}
