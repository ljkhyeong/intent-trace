package io.intenttrace.intellij

import java.net.URI

internal object GitHubEvidenceLinks {
    fun commit(record: ChangeIntentRecord): URI = uri(record, "commit")

    fun code(record: ChangeIntentRecord, anchor: ChangeCodeAnchor): URI {
        if (anchor.relativePath.startsWith('/') || anchor.relativePath.split('/').any { it == "." || it == ".." } ||
            '\\' in anchor.relativePath || anchor.startLine < 1 || anchor.endLine < anchor.startLine
        ) {
            throw IntentTraceUsageException("기록의 코드 위치를 열 수 없습니다.")
        }
        return uri(record, "blob", "/${anchor.relativePath}", "L${anchor.startLine}-L${anchor.endLine}")
    }

    private fun uri(record: ChangeIntentRecord, kind: String, suffix: String = "", fragment: String? = null): URI {
        if (!record.repositoryKey.matches(Regex("^[a-z0-9_.-]+/[a-z0-9_.-]+$")) ||
            record.repositoryKey.split('/').any { it == "." || it == ".." } ||
            record.targetRevision?.matches(Regex("^(?:[0-9a-f]{40}|[0-9a-f]{64})$")) != true
        ) {
            throw IntentTraceUsageException("기록의 GitHub 저장소와 전체 커밋을 확인할 수 없습니다.")
        }
        return URI("https", "github.com", "/${record.repositoryKey}/$kind/${record.targetRevision}$suffix", null, fragment)
    }
}
