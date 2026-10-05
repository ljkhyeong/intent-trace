package io.intenttrace.intellij

import java.net.URI

internal object GitHubEvidenceLinks {
    fun commit(record: ChangeIntentRecord): URI = uri(record, record.targetRevision, "commit")

    fun code(record: ChangeIntentRecord, anchor: ChangeCodeAnchor): URI {
        if (anchor.relativePath.startsWith('/') || anchor.relativePath.split('/').any { it == "." || it == ".." } ||
            '\\' in anchor.relativePath || anchor.startLine < 1 || anchor.endLine < anchor.startLine
        ) {
            throw IntentTraceUsageException("기록의 코드 위치를 열 수 없습니다.")
        }
        return uri(record, record.revisionFor(anchor), "blob", "/${anchor.relativePath}", "L${anchor.startLine}-L${anchor.endLine}")
    }

    private fun uri(record: ChangeIntentRecord, revision: String?, kind: String, suffix: String = "", fragment: String? = null): URI {
        if (!REPOSITORY_KEY.matches(record.repositoryKey) || record.repositoryKey.split('/').any { it == "." || it == ".." } ||
            revision == null || !FULL_REVISION.matches(revision)
        ) {
            throw IntentTraceUsageException("기록의 GitHub 저장소와 전체 커밋을 확인할 수 없습니다.")
        }
        return URI("https", "github.com", "/${record.repositoryKey}/$kind/$revision$suffix", null, fragment)
    }
}
