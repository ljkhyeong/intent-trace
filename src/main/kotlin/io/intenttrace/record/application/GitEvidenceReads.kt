package io.intenttrace.record.application

import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.publication.application.GitHubApiException

enum class EvidenceUnavailableReason { SIZE_LIMIT, TRUNCATED_TREE, UNSUPPORTED_OBJECT }

class EvidenceUnavailableException(val reason: EvidenceUnavailableReason) :
    GitHubApiException("현재 코드 확인에서 지원하지 않는 Git 객체입니다: ${reason.name}")

// 조회 한 번의 후보들이 공유한다. 코드 원문과 실패 원인은 요청이 끝나면 버린다.
internal class GitEvidenceReads(private val repository: GitHubRepository, private val gateway: GitEvidenceGateway, private val budget: EvidenceReadBudget) {
    private val snapshots = bounded<GitEvidenceSnapshot>(8)
    private val blobs = bounded<ByteArray>(8)
    private val ancestry = bounded<Boolean>(40)

    fun snapshot(revision: String) = read(snapshots, revision) { gateway.snapshot(repository, revision, budget) }
    fun blob(sha: String) = read(blobs, sha) { gateway.blob(repository, sha, budget) }
    fun isAncestor(from: String, to: String) = read(ancestry, "$from:$to") { gateway.isAncestor(repository, from, to, budget) }

    private fun <T> read(cache: MutableMap<String, Result<T>>, key: String, fetch: () -> T): T =
        cache.getOrPut(key) {
            try { Result.success(fetch()) } catch (failure: EvidenceUnavailableException) { Result.failure(failure) }
        }.getOrThrow()

    private fun <T> bounded(limit: Int) = object : LinkedHashMap<String, Result<T>>(limit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result<T>>): Boolean = size > limit
    }
}
