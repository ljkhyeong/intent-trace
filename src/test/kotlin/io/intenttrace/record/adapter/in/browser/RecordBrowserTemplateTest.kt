package io.intenttrace.record.adapter.`in`.browser

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordBrowserTemplateTest {
    @Test
    fun `템플릿은 이스케이프하지 않는 출력과 전처리·bean 참조 식을 쓰지 않는다`() {
        val forbidden = Regex("""th:utext|\[\(|__\$\{|\$\{@""")
        val templates = Files.walk(Path.of("src/main/resources/templates")).use { paths -> paths.filter { it.extension == "html" }.toList() }
        assertTrue(templates.isNotEmpty())
        templates.forEach { assertNull(forbidden.find(it.readText())?.value, it.toString()) }
    }
}
