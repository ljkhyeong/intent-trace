package io.intenttrace.intellij

import kotlin.test.Test
import kotlin.test.assertContains

class IntentTraceTextRendererTest {
    @Test
    fun `근거 출처와 snapshot 상태를 구분해서 표시한다`() {
        val output = IntentTraceTextRenderer.render(
            lookup = LineLookup("team/repository", "a".repeat(40), "src/main/App.kt", 12),
            records = listOf(
                ChangeIntentRecord(
                    id = "record-1",
                    title = "현재 줄 변경 의도",
                    requestSummary = "팀원이 변경 이유를 확인한다.",
                    status = "PUBLISHED",
                    authorLogin = "developer",
                    decisions = listOf(ChangeDecision("얇은 IDE client를 둔다.", null, "INFERRED")),
                    codeAnchors = listOf(ChangeCodeAnchor("src/main/App.kt", 10, 15)),
                    verifications = listOf(ChangeVerification("./gradlew test", 0, "통과", false)),
                    openQuestions = emptyList(),
                    repositoryKey = "team/repository",
                    targetRevision = "a".repeat(40),
                    supersededBy = null,
                ),
            ),
        )

        assertContains(output, "[추론] 얇은 IDE client를 둔다.")
        assertContains(output, "[기록 스냅샷과 불일치, exit 0] ./gradlew test")
        assertContains(output, "미확인 항목\n- 없음")
    }
}
