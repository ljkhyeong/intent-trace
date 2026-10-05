package io.intenttrace.record.adapter.out.github

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.config.GitHubApiException
import io.intenttrace.config.readJsonWithin
import io.intenttrace.record.application.EvidenceReadBudget
import org.springframework.http.client.JdkClientHttpRequestFactory
import java.net.http.HttpClient
import java.time.Duration
import io.intenttrace.record.application.EvidenceUnavailableException
import io.intenttrace.record.application.EvidenceUnavailableReason
import io.intenttrace.record.application.GitEvidenceGateway
import io.intenttrace.record.application.GitEvidenceSnapshot
import io.intenttrace.record.application.GitTreeEntry
import io.intenttrace.record.domain.requireFullRevision
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper
import java.util.Base64

@Component
class GitHubGitEvidenceClient(
    @Qualifier("githubApiRestClient") private val client: RestClient,
    private val session: CurrentGitHubUserSession,
    private val mapper: ObjectMapper,
) : GitEvidenceGateway {
    private val budgetHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()

    override fun snapshot(repository: GitHubRepository, revision: String, budget: EvidenceReadBudget?): GitEvidenceSnapshot {
        val ref = requireFullRevision(revision)
        // 저장소 읽기 권한을 확인한 뒤 읽으므로 커밋 조회의 404·422는 GitHub에 없는 커밋이다.
        val commit = get(repository, "/git/commits/$ref", CommitResponse::class.java, budget, EvidenceUnavailableReason.REVISION_NOT_FOUND)
        if (commit.sha != ref) throw GitHubApiException("GitHub 커밋 응답이 요청 커밋과 다릅니다.")
        val tree = get(repository, "/git/trees/${parseResponseRevision(commit.tree.sha)}?recursive=1", TreeResponse::class.java, budget)
        if (tree.truncated == true) throw EvidenceUnavailableException(EvidenceUnavailableReason.TRUNCATED_TREE)
        if (tree.truncated != false || tree.sha != commit.tree.sha) throw GitHubApiException("GitHub 전체 트리를 확인할 수 없습니다.")
        val entries = tree.tree.associateBy({ it.path }, { GitTreeEntry(it.path, it.mode, it.type, it.sha) })
        if (entries.size != tree.tree.size) throw GitHubApiException("GitHub 트리의 경로가 중복됐습니다.")
        entries.values.forEach {
            if (it.mode !in setOf("100644", "100755", "120000", "160000", "040000") || it.type !in setOf("blob", "commit", "tree")) {
                throw EvidenceUnavailableException(EvidenceUnavailableReason.UNSUPPORTED_OBJECT)
            }
            parseResponseRevision(it.sha)
        }
        return GitEvidenceSnapshot(entries)
    }

    override fun blob(repository: GitHubRepository, sha: String, budget: EvidenceReadBudget?): ByteArray {
        val blob = get(repository, "/git/blobs/${requireFullRevision(sha)}", BlobResponse::class.java, budget)
        if (blob.size > MAX_BLOB_SIZE) throw EvidenceUnavailableException(EvidenceUnavailableReason.SIZE_LIMIT)
        if (blob.encoding != "base64") throw EvidenceUnavailableException(EvidenceUnavailableReason.UNSUPPORTED_OBJECT)
        if (blob.sha != sha || blob.size < 0) {
            throw GitHubApiException("GitHub 코드 파일 형식 또는 크기를 확인할 수 없습니다.")
        }
        val bytes = try { Base64.getMimeDecoder().decode(blob.content) } catch (_: IllegalArgumentException) {
            throw GitHubApiException("GitHub 코드 파일 인코딩을 확인할 수 없습니다.")
        }
        if (bytes.size != blob.size) throw GitHubApiException("GitHub 코드 파일 크기가 일치하지 않습니다.")
        return bytes
    }

    override fun isAncestor(repository: GitHubRepository, ancestor: String, descendant: String, budget: EvidenceReadBudget?): Boolean {
        if (ancestor == descendant) return true
        val result = get(repository, "/compare/${requireFullRevision(ancestor)}...${requireFullRevision(descendant)}?per_page=1", CompareResponse::class.java, budget)
        return when (result.status) {
            "ahead", "identical" -> true
            "behind", "diverged" -> false
            else -> throw GitHubApiException("GitHub 커밋 비교 결과를 확인할 수 없습니다.")
        }
    }

    private fun parseResponseRevision(value: String): String = try {
        requireFullRevision(value)
    } catch (_: IllegalArgumentException) {
        throw GitHubApiException("GitHub 코드 응답의 객체 해시 형식이 올바르지 않습니다.")
    }

    private fun <T> get(
        repository: GitHubRepository,
        suffix: String,
        type: Class<T>,
        budget: EvidenceReadBudget?,
        notFound: EvidenceUnavailableReason? = null,
    ): T = try {
        val remaining = budget?.beforeRemoteCall()
        val requestClient = if (remaining == null) client else client.mutate().requestFactory(
            JdkClientHttpRequestFactory(budgetHttpClient).apply { setReadTimeout(remaining.coerceAtMost(Duration.ofSeconds(10))) },
        ).build()
        requestClient.get().uri("/repos/${repository.key}$suffix")
            .headers { it.setBearerAuth(session.require().accessToken) }
            .exchange { _, response ->
                if (response.statusCode.value() == 401) throw GitHubUserAuthenticationException()
                if (notFound != null && response.statusCode.value() in setOf(404, 422)) throw EvidenceUnavailableException(notFound)
                if (!response.statusCode.is2xxSuccessful) throw GitHubApiException("GitHub 코드 조회 실패. HTTP ${response.statusCode.value()}")
                response.readJsonWithin(mapper, type, MAX_RESPONSE_SIZE) {
                    throw EvidenceUnavailableException(EvidenceUnavailableReason.SIZE_LIMIT)
                }.also { budget?.checkpoint() }
            } ?: throw GitHubApiException("GitHub 코드 응답이 비어 있습니다.")
    } catch (_: RestClientException) {
        budget?.checkpoint()
        throw GitHubApiException("GitHub 코드 조회를 완료하지 못했습니다.")
    }

    companion object {
        private const val MAX_BLOB_SIZE = 2 * 1024 * 1024
        private const val MAX_RESPONSE_SIZE = 8 * 1024 * 1024
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class CommitResponse(val sha: String, val tree: TreeReference)
@JsonIgnoreProperties(ignoreUnknown = true)
private data class TreeReference(val sha: String)
@JsonIgnoreProperties(ignoreUnknown = true)
private data class TreeResponse(val sha: String, val tree: List<TreeEntryResponse>, val truncated: Boolean? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
private data class BlobResponse(val sha: String, val encoding: String, val size: Int, val content: String)
@JsonIgnoreProperties(ignoreUnknown = true)
private data class CompareResponse(val status: String)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TreeEntryResponse(val path: String, val mode: String, val type: String, val sha: String)
