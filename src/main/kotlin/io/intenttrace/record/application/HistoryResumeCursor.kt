package io.intenttrace.record.application

import java.time.Instant
import java.util.Base64
import java.util.UUID

// 공개 본문은 고정되어 있으므로 근거 순서로 이어 읽을 수 있다. 원문과 인증 정보는 넣지 않는다.
internal data class HistoryResumeCursor(
    val queryDigest: String,
    val record: RecordCursor,
    val anchorIndex: Int,
    val remainingCandidates: Int,
    val hasMore: Boolean,
) {
    fun encode(): String = "h1." + Base64.getUrlEncoder().withoutPadding().encodeToString(
        "$queryDigest|${record.createdAt}|${record.id}|$anchorIndex|$remainingCandidates|$hasMore".toByteArray(Charsets.UTF_8),
    )

    companion object {
        fun queryDigest(repository: String, revision: String, path: String, line: Int) = GitEvidenceDigest.sha256(
            listOf(repository, revision, path, line.toString()).joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8),
        )

        fun parse(value: String, queryDigest: String): HistoryResumeCursor = try {
            require(value.startsWith("h1.") && value.length <= 512)
            val parts = String(Base64.getUrlDecoder().decode(value.substring(3)), Charsets.UTF_8).split('|')
            require(parts.size == 6 && parts[0] == queryDigest)
            HistoryResumeCursor(parts[0], RecordCursor(Instant.parse(parts[1]), UUID.fromString(parts[2])),
                parts[3].toInt(), parts[4].toInt(), parts[5].toBooleanStrict()).also {
                require(it.anchorIndex >= 0 && it.remainingCandidates in 1..20)
            }
        } catch (_: RuntimeException) { throw IllegalArgumentException("같은 조회 조건의 재개 커서를 사용해 주세요.") }
    }
}
