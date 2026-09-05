package io.intenttrace.record.application

import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.GitRevision
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

data class GitTreeEntry(val path: String, val mode: String, val type: String, val sha: String)

data class GitEvidenceSnapshot(val revision: String, val entries: Map<String, GitTreeEntry>) {
    val digest: String get() = GitEvidenceDigest.snapshot(entries.values.toList())
}

interface GitEvidenceGateway {
    fun snapshot(repository: GitHubRepository, revision: String, budget: EvidenceReadBudget? = null): GitEvidenceSnapshot
    fun blob(repository: GitHubRepository, sha: String, budget: EvidenceReadBudget? = null): ByteArray
    fun isAncestor(repository: GitHubRepository, ancestor: String, descendant: String, budget: EvidenceReadBudget? = null): Boolean
}

object GitEvidenceDigest {
    fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun snapshot(entries: List<GitTreeEntry>): String {
        val rows = entries.filter { it.type != "tree" }.sortedWith { left, right ->
            java.util.Arrays.compareUnsigned(left.path.toByteArray(Charsets.UTF_8), right.path.toByteArray(Charsets.UTF_8))
        }.joinToString("") { "${it.mode} ${it.type} ${it.sha}\t${quotePath(it.path)}\n" }
        return sha256(rows.toByteArray(Charsets.UTF_8))
    }

    private fun quotePath(path: String): String {
        val bytes = path.toByteArray(Charsets.UTF_8)
        if (bytes.all { (it.toInt() and 255) in 32..126 && it != '"'.code.toByte() && it != '\\'.code.toByte() }) return path
        return buildString {
            append('"')
            bytes.forEach {
                val value = it.toInt() and 255
                append(when (value) {
                    7 -> "\\a"; 8 -> "\\b"; 9 -> "\\t"; 10 -> "\\n"; 11 -> "\\v"; 12 -> "\\f"; 13 -> "\\r"
                    34 -> "\\\""; 92 -> "\\\\"
                    in 32..126 -> value.toChar().toString()
                    else -> "\\" + value.toString(8).padStart(3, '0')
                })
            }
            append('"')
        }
    }

    fun lines(bytes: ByteArray, start: Int, end: Int): String? {
        if (start < 1 || end < start) return null
        var line = 1
        var from = if (start == 1) 0 else -1
        bytes.forEachIndexed { index, byte ->
            if (byte == 10.toByte()) {
                if (line == end && from >= 0) return sha256(bytes.copyOfRange(from, index + 1))
                line++
                if (line == start) from = index + 1
            }
        }
        return if (line == end && from in 0 until bytes.size && bytes.last() != 10.toByte()) {
            sha256(bytes.copyOfRange(from, bytes.size))
        } else null
    }
}

enum class AnchorCheckStatus { MATCHED, HASH_MISMATCH, FILE_MISSING, LINE_RANGE_MISSING, UNSUPPORTED_OBJECT }

data class AnchorEvidenceCheck(val path: String, val side: CodeSide, val revision: String, val startLine: Int, val endLine: Int, val status: AnchorCheckStatus)

data class RecordEvidenceCheck(
    val recordId: UUID,
    val recordVersion: Long,
    val targetRevision: String,
    val snapshotDigest: String,
    val checkedAt: Instant,
    val snapshotMatches: Boolean,
    val anchors: List<AnchorEvidenceCheck>,
    val codeVerified: Boolean,
    val serverExecutionVerified: Boolean = false,
)

@Service
class RecordEvidenceService(
    private val records: TeamChangeRecordService,
    private val gateway: GitEvidenceGateway,
    private val clock: Clock,
) {
    fun check(recordId: UUID): RecordEvidenceCheck {
        val record = records.get(recordId)
        val revision = checkNotNull(record.targetRevision) { "전체 커밋을 확인한 기록만 서버 코드 확인을 요청할 수 있습니다." }
        val repository = GitHubRepository.parse(record.repositoryKey)
        val snapshots = mutableMapOf<String, GitEvidenceSnapshot>()
        fun snapshot(ref: String) = snapshots.getOrPut(ref) { gateway.snapshot(repository, GitRevision.parse(ref).value) }
        val target = snapshot(revision)
        val blobs = mutableMapOf<String, ByteArray>()
        val checks = record.codeAnchors.map { anchor ->
            val ref = if (anchor.side == CodeSide.BASE) checkNotNull(record.baseRevision) else revision
            val entry = snapshot(ref).entries[anchor.relativePath]
            val status = when {
                entry == null -> AnchorCheckStatus.FILE_MISSING
                entry.type != "blob" -> AnchorCheckStatus.UNSUPPORTED_OBJECT
                else -> {
                    val hash = GitEvidenceDigest.lines(blobs.getOrPut(entry.sha) { gateway.blob(repository, entry.sha) }, anchor.startLine, anchor.endLine)
                    when (hash) {
                        null -> AnchorCheckStatus.LINE_RANGE_MISSING
                        anchor.contentHash -> AnchorCheckStatus.MATCHED
                        else -> AnchorCheckStatus.HASH_MISMATCH
                    }
                }
            }
            AnchorEvidenceCheck(anchor.relativePath, anchor.side, ref, anchor.startLine, anchor.endLine, status)
        }
        val matches = record.snapshotDigest == target.digest
        return RecordEvidenceCheck(record.id, record.version, revision, record.snapshotDigest, Instant.now(clock), matches, checks,
            matches && checks.all { it.status == AnchorCheckStatus.MATCHED })
    }
}
