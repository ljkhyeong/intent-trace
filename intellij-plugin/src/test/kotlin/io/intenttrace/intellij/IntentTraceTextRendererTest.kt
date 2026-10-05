package io.intenttrace.intellij

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class IntentTraceTextRendererTest {
    @Test
    fun `근거 출처와 snapshot 상태를 구분해서 표시한다`() {
        val output = IntentTraceTextRenderer.render(
            lookup = LineLookup("team/repository", "a".repeat(40), "src/main/App.kt", 12),
            found = ChangeIntentLookup(listOf(record), truncated = true),
        )

        assertContains(output, "[정황에서 추론] 얇은 IDE client를 둔다.")
        assertContains(output, "[기록 스냅샷과 불일치, 종료 코드 0] ./gradlew test")
        assertContains(output, "남은 질문\n- 없음")
        assertContains(output, "최근 공개 기록 1건만 표시합니다.")
    }

    @Test
    fun `변경 전 줄의 검증 결과는 스냅샷 불일치로 단정하지 않는다`() {
        val lookup = LineLookup(record.repositoryKey, "b".repeat(40), "src/main/App.kt", 12)
        for (current in listOf(false, true)) {
            val response = record.copy(baseRevision = lookup.revision,
                verifications = record.verifications.map { it.copy(current = current) })
            val output = IntentTraceTextRenderer.render(lookup, ChangeIntentLookup(listOf(response), truncated = false))
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

    @Test
    fun `연결 진단은 항목과 상태를 한국어로 표시하고 게시·테스트 실행을 확인하지 않았다고 안내한다`() {
        val output = IntentTraceTextRenderer.renderDiagnosis(ConnectionDiagnosis("team/repository", "2026-10-05T01:00:00Z", listOf(
            ConnectionCheck("repository_write", "FAILED", "대상 저장소의 접근 권한을 확인할 수 없습니다."),
            ConnectionCheck("publication_credentials", "CONFIGURED_UNVERIFIED", ""),
            ConnectionCheck("future_check", "NOT_CHECKED"),
        )), "c".repeat(40))
        assertContains(output, "team/repository · 커밋 cccccccccccc")
        assertContains(output, "[실패] 저장소 쓰기 권한 — 대상 저장소의 접근 권한을 확인할 수 없습니다.")
        assertContains(output, "[설정됨·미확인] 서버 게시 설정\n")
        assertContains(output, "[확인 안 함] future_check")
        assertContains(output, "실제 게시나 테스트 실행은 하지 않습니다.")
        assertContains(IntentTraceTextRenderer.renderDiagnosis(ConnectionDiagnosis("team/repository", "t", emptyList()), null), "커밋 미지정")
    }

    @Test
    fun `이전 커밋 결과는 일치 방식과 원본 커밋을 보여 주고 과거 검증을 현재 검증으로 표시하지 않는다`() {
        val lookup = LineLookup("team/repository", "a".repeat(40), "src/main/App.kt", 12)
        val view = LineHistoryView.of(ChangeIntentHistory("a".repeat(40), lookup.relativePath, listOf(HistoricalIntent(
            ChangeRecordSummary("record-1", "이전 기록", "PUBLISHED", "b".repeat(40), CreatedByResponse("developer"), "2026-10-01T00:00:00Z"),
            "b".repeat(40), CodeSide.BASE, "ANCESTOR_MOVED_LINES", false, "src/Old.kt", 4, 6, 10, 12,
        )), "h1.next", 3, listOf(HistoryFailure("record-2", "REVISION_NOT_FOUND")), "CALL_LIMIT", false, false))

        val output = IntentTraceTextRenderer.renderLineHistory(lookup, view)

        assertContains(output, "1. [코드 줄 이동 확인] 이전 기록 · @developer · 팀 공개")
        assertContains(output, "원본: src/Old.kt:4-6 · 변경 전 · 커밋 bbbbbbbbbbbb")
        assertContains(output, "조회한 커밋의 줄: 10-12")
        assertContains(output, "이 기록의 테스트 결과로 조회한 커밋이 검증됐다고 볼 수 없습니다.")
        assertContains(output, "이번 조회의 GitHub 호출 한도에 도달했습니다. 중단 위치부터 계속 조회할 수 있습니다.")
        assertContains(output, "- record-2: GitHub에서 커밋을 찾을 수 없습니다. 원격 저장소에 푸시했는지 확인하세요.")
    }

    private val record = ChangeIntentRecord(
        id = "record-1", title = "현재 줄 변경 의도", requestSummary = "팀원이 변경 이유를 확인한다.",
        status = "PUBLISHED", createdBy = CreatedByResponse("developer"),
        decisions = listOf(ChangeDecision("얇은 IDE client를 둔다.", null, "INFERRED")),
        codeAnchors = listOf(ChangeCodeAnchor("src/main/App.kt", 10, 15)),
        verifications = listOf(ChangeVerification("./gradlew test", 0, "통과", false)),
        openQuestions = emptyList(), repositoryKey = "team/repository",
        targetRevision = "a".repeat(40), supersededBy = null,
    )
}
