package io.intenttrace

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 외부 명령의 종료 코드와 stdout·stderr를 합친 출력. */
data class ProcessResult(val exitCode: Int, val output: String) {
    fun assertSuccess(): ProcessResult = also { assertEquals(0, exitCode, output) }
}

/**
 * 명령을 실행하고 30초 안에 끝나기를 기다린다. 출력은 임시 파일로 받아 파이프가 차도 멈추지 않고,
 * 제한 시간을 넘기면 자식 프로세스까지 종료한다. 실패 메시지에는 환경 변수 값을 넣지 않는다.
 */
fun runProcess(command: List<String>, directory: Path? = null, environment: Map<String, String> = emptyMap()): ProcessResult {
    val log = Files.createTempFile("intent-trace-process", ".log")
    try {
        val builder = ProcessBuilder(command).directory(directory?.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
        builder.environment().putAll(environment)
        val process = builder.start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        // 강제 종료로 끊긴 한글 출력도 읽을 수 있도록 잘못된 바이트는 대체 문자로 바꾼다.
        val output = String(Files.readAllBytes(log), Charsets.UTF_8)
        assertTrue(finished, "${command.first()} 실행이 30초 안에 끝나야 합니다.\n$output")
        return ProcessResult(process.exitValue(), output)
    } finally {
        Files.deleteIfExists(log)
    }
}

/** clients/zed의 Node 의존성으로 ES 모듈 스크립트를 실행한다. */
fun runZedNode(script: String, environment: Map<String, String>): ProcessResult =
    runProcess(listOf("node", "--input-type=module", "-e", script), Path.of("clients/zed"), environment)
