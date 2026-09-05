package io.intenttrace.record.application

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GitEvidenceScriptTest {
    @TempDir
    lateinit var repository: Path

    private lateinit var revision: String

    @BeforeEach
    fun prepareRepository() {
        runGit("init")
        Files.writeString(repository.resolve("sample.txt"), "첫 줄\n둘째 줄\n")
        Files.writeString(repository.resolve("empty.txt"), "")
        Files.createDirectory(repository.resolve("src"))
        Files.writeString(repository.resolve("src/한글.txt"), "한글 파일명도 같은 해시를 만든다.\n")
        runGit("add", ".")
        runGit("-c", "user.name=IntentTrace Test", "-c", "user.email=test@intenttrace.local", "commit", "-m", "테스트 파일 추가")
        revision = runGit("rev-parse", "HEAD").output.trim()
    }

    @Test
    fun `코드 근거는 실제 파일의 존재하는 줄 범위만 허용한다`() {
        val exactRange = runEvidence("anchor", revision, "sample.txt", "2", "2")
        val pastEnd = runEvidence("anchor", revision, "sample.txt", "2", "3")
        val emptyFile = runEvidence("anchor", revision, "empty.txt", "1", "1")
        val directory = runEvidence("anchor", revision, "src", "1", "1")
        val missingFile = runEvidence("anchor", revision, "missing.txt", "1", "1")

        assertEquals(0, exactRange.exitCode)
        assertEquals(64, exactRange.output.trim().length)
        assertNotEquals(0, pastEnd.exitCode)
        assertNotEquals(0, emptyFile.exitCode)
        assertNotEquals(0, directory.exitCode)
        assertNotEquals(0, missingFile.exitCode)
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

    @Test
    fun `비교할 수 없는 큰 줄 번호는 해시 없이 실패한다`() {
        val overflow = "9223372036854775808"
        for (start in listOf("1", overflow)) {
            val result = runEvidence("anchor", revision, "sample.txt", start, overflow)

            assertNotEquals(0, result.exitCode, result.output)
            assertFalse(result.output.lineSequence().any { it.matches(Regex("[0-9a-f]{64}")) }, result.output)
        }
    }

    @Test
    fun `스냅샷은 개인 quotePath 설정과 무관하고 기존 기본 출력의 해시를 유지한다`() {
        val quoted = runGit("-c", "core.quotePath=true", "ls-tree", "-r", "--full-tree", revision)
        val unquoted = runGit("-c", "core.quotePath=false", "ls-tree", "-r", "--full-tree", revision)
        val expected = sha256(quoted.output)
        assertNotEquals(expected, sha256(unquoted.output))

        for (setting in listOf("true", "false")) {
            runGit("config", "core.quotePath", setting)
            val snapshot = runEvidence("snapshot", revision)

            assertEquals(0, snapshot.exitCode, snapshot.output)
            assertEquals(expected, snapshot.output.trim())
        }
    }

    @Test
    fun `Git 트리 조회가 실패하면 빈 스냅샷 해시를 반환하지 않는다`() {
        val tree = runGit("rev-parse", "HEAD^{tree}").output.trim()
        val objectPath = repository.resolve(
            runGit("rev-parse", "--git-path", "objects/${tree.take(2)}/${tree.drop(2)}").output.trim(),
        )
        val missingObjectPath = objectPath.resolveSibling("${objectPath.fileName}.missing")
        Files.move(objectPath, missingObjectPath)

        try {
            val result = runEvidence("snapshot", revision)

            assertNotEquals(0, result.exitCode, result.output)
            assertFalse(result.output.lineSequence().any { it.matches(Regex("[0-9a-f]{64}")) }, result.output)
        } finally {
            Files.move(missingObjectPath, objectPath)
        }
    }

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    private fun runGit(vararg arguments: String): CommandResult =
        runCommand(listOf("git", *arguments)).also { assertEquals(0, it.exitCode, it.output) }

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
