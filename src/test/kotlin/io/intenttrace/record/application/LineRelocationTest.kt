package io.intenttrace.record.application

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineRelocationTest {
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
