package io.intenttrace.record.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `서버 스냅샷과 줄 해시는 Git helper의 경로 인용과 마지막 줄 규칙을 따른다`() {
        runGit("init")
        Files.writeString(repository.resolve("한글 파일.txt"), "첫 줄\r\n마지막 줄")
        Files.createDirectories(repository.resolve("dir"))
        Files.writeString(repository.resolve("dir/quote\".txt"), "다른 파일\n")
        runGit("add", ".")
        runGit("-c", "user.name=IntentTrace Test", "-c", "user.email=test@intenttrace.local", "commit", "-m", "해시 규칙 확인")
        val revision = runGit("rev-parse", "HEAD").output.trim()
        val entries = runGit("ls-tree", "-r", "-z", revision).output.split('\u0000').filter { it.isNotEmpty() }.map {
            val (header, path) = it.split('\t', limit = 2)
            val (mode, type, sha) = header.split(' ')
            GitTreeEntry(path, mode, type, sha)
        }
        assertEquals(runEvidence("snapshot", revision).output.trim(), GitEvidenceDigest.snapshot(entries))
        assertEquals(runEvidence("anchor", revision, "한글 파일.txt", "2", "2").output.trim(),
            GitEvidenceDigest.lines(Files.readAllBytes(repository.resolve("한글 파일.txt")), 2, 2))
    }

    @Test
    fun `실행 도구는 실패 종료 코드와 출력 해시를 수집하고 변경된 작업 트리는 거부한다`() {
        runGit("init")
        Files.writeString(repository.resolve("sample.txt"), "저장하면 안 되는 원문\n")
        runGit("add", ".")
        runGit("-c", "user.name=IntentTrace Test", "-c", "user.email=test@intenttrace.local", "commit", "-m", "실행 검증")
        val revision = runGit("rev-parse", "HEAD").output.trim()
        val script = Path.of("scripts/run-verification.py").toAbsolutePath().toString()
        val captured = runCommand(listOf("python3", script, revision, "--summary", "실패 경로 확인", "--", "python3", "-c",
            "from pathlib import Path; import sys; sys.stdout.write(Path('sample.txt').read_text()); sys.exit(7)"))
        assertEquals(7, captured.exitCode)
        assertTrue(captured.output.contains("\"exitCode\": 7"))
        assertTrue(captured.output.contains(GitEvidenceDigest.sha256(Files.readAllBytes(repository.resolve("sample.txt")))))
        assertFalse(captured.output.contains("저장하면 안 되는 원문"))
        val changed = runCommand(listOf("python3", script, revision, "--summary", "변경 감지", "--", "python3", "-c",
            "from pathlib import Path; Path('sample.txt').write_text('changed')"))
        assertEquals(2, changed.exitCode)
        assertFalse(changed.output.contains("\"snapshotDigest\""))
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
