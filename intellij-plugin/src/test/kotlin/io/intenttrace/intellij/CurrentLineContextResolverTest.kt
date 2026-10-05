package io.intenttrace.intellij

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CurrentLineContextResolverTest {
    private val lookup = LineLookup("team/repository", "a".repeat(40), "src/App.kt", 12)

    @Test
    fun `조회 직전 다시 읽은 HEAD나 파일 상태가 다르면 조회하지 않는다`() {
        CurrentLineContextResolver.requireUnchanged(lookup, FreshLineState("a".repeat(40), fileChanged = false))

        for ((state, message) in listOf(
            FreshLineState("b".repeat(40), false) to "조회를 시작한 뒤 Git HEAD가 바뀌었습니다. 편집기에 새 커밋이 반영된 뒤 다시 조회해 주세요.",
            FreshLineState(null, false) to "조회를 시작한 뒤 Git HEAD가 바뀌었습니다. 편집기에 새 커밋이 반영된 뒤 다시 조회해 주세요.",
            FreshLineState("a".repeat(40), true) to "현재 파일에 커밋되지 않은 변경이 있습니다. HEAD 기준 줄을 조회하려면 먼저 커밋해 주세요.",
        )) {
            assertEquals(message, assertFailsWith<IntentTraceUsageException> {
                CurrentLineContextResolver.requireUnchanged(lookup, state)
            }.message)
        }
    }
}
