package io.intenttrace.record.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GitEvidenceScriptTest {
    @TempDir
    lateinit var repository: Path

    @Test
    fun `코드 근거 줄 범위가 파일 끝을 넘으면 실패한다`() {
        runGit("init")
        Files.writeString(repository.resolve("sample.txt"), "첫 줄\n둘째 줄\n")
        Files.writeString(repository.resolve("empty.txt"), "")
        runGit("add", ".")
        runGit("-c", "user.name=IntentTrace Test", "-c", "user.email=test@intenttrace.local", "commit", "-m", "테스트 파일 추가")
        val revision = runGit("rev-parse", "HEAD").output.trim()

        val exactRange = runEvidence("anchor", revision, "sample.txt", "2", "2")
        val pastEnd = runEvidence("anchor", revision, "sample.txt", "2", "3")
        val emptyFile = runEvidence("anchor", revision, "empty.txt", "1", "1")

        assertEquals(0, exactRange.exitCode)
        assertEquals(64, exactRange.output.trim().length)
        assertNotEquals(0, pastEnd.exitCode)
        assertNotEquals(0, emptyFile.exitCode)
    }

    private fun runGit(vararg arguments: String): CommandResult =
        runCommand(listOf("git", *arguments))

    private fun runEvidence(vararg arguments: String): CommandResult {
        val script = Path.of("scripts/git-evidence.sh").toAbsolutePath()
        return runCommand(listOf("/bin/sh", script.toString(), *arguments))
    }

    private fun runCommand(command: List<String>): CommandResult {
        val process = ProcessBuilder(command)
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return CommandResult(process.waitFor(), output)
    }

    private data class CommandResult(val exitCode: Int, val output: String)
}
