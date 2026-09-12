package io.intenttrace.record.application

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineRelocationTest {
    @Test
    fun `다른 들여쓰기나 주석의 부분 일치는 전체 줄 중복으로 세지 않는다`() {
        for (source in listOf("return value;\n    return value;\n", "    return value;\nreturn value;\n")) {
            val line = if (source.startsWith("return")) 1 else 2
            val target = "// return value;\n" + source + "// return value;\n"
            assertEquals((line + 1)..(line + 1), LineRelocation.find(source.toByteArray(), target.toByteArray(), line, line))
        }
        assertEquals(2..2, LineRelocation.find("끝".toByteArray(), "접두어끝\n끝".toByteArray(), 1, 1))
    }

    @Test
    fun `서로 겹치는 여러 줄 조각도 중복으로 처리한다`() {
        val repeated = "a\na\na\n".toByteArray()
        val fragment = "a\na\n".toByteArray()
        assertNull(LineRelocation.find(fragment, repeated, 1, 2))
        assertNull(LineRelocation.find(repeated, fragment, 1, 2))
    }

    @Test
    fun `줄 끝 바이트를 유지하고 중복 코드와 바뀐 코드를 이동으로 처리하지 않는다`() {
        val source = "처음\r\n고유 코드\r\n마지막".toByteArray()
        assertEquals(3..3, LineRelocation.find(source, "추가\r\n".toByteArray() + source, 2, 2))
        assertEquals(4..4, LineRelocation.find(source, "추가\r\n".toByteArray() + source, 3, 3))
        assertNull(LineRelocation.find(source, source + source, 2, 2))
        assertNull(LineRelocation.find(source + source, source, 2, 2))
        assertNull(LineRelocation.find(source, "처음\n고유 코드\n마지막".toByteArray(), 2, 2))
        assertNull(LineRelocation.find(source, "접두어고유 코드\r\n".toByteArray(), 2, 2))
    }
}
