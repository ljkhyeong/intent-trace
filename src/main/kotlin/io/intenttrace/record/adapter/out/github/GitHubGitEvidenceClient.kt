package io.intenttrace.record.adapter.out.github

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.CurrentGitHubUserSession
import io.intenttrace.identity.application.GitHubUserAuthenticationException
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.publication.application.GitHubApiException
import io.intenttrace.record.application.GitEvidenceGateway
import io.intenttrace.record.application.GitEvidenceSnapshot
import io.intenttrace.record.application.GitTreeEntry
import io.intenttrace.record.domain.GitRevision
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper
import java.util.Base64

@Component
class GitHubGitEvidenceClient(
    builder: RestClient.Builder,
    properties: GitHubProperties,
    private val session: CurrentGitHubUserSession,
    private val mapper: ObjectMapper,
) : GitEvidenceGateway {
    private val client = builder.baseUrl(properties.apiBaseUrl.toString().trimEnd('/'))
        .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
        .defaultHeader("X-GitHub-Api-Version", properties.apiVersion).build()

    override fun snapshot(repository: GitHubRepository, revision: String): GitEvidenceSnapshot {
        val ref = GitRevision.parse(revision).value
        val commit = get(repository, "/git/commits/$ref", CommitResponse::class.java)
        if (commit.sha != ref) throw GitHubApiException("GitHub 커밋 응답이 요청 커밋과 다릅니다.")
        val tree = get(repository, "/git/trees/${GitRevision.parse(commit.tree.sha).value}?recursive=1", TreeResponse::class.java)
        if (tree.truncated != false || tree.sha != commit.tree.sha) throw GitHubApiException("GitHub 전체 트리를 확인할 수 없습니다.")
        if (tree.tree.map { it.path }.distinct().size != tree.tree.size) throw GitHubApiException("GitHub 트리의 경로가 중복됐습니다.")
        tree.tree.forEach {
            if (it.mode !in setOf("100644", "100755", "120000", "160000", "040000") || it.type !in setOf("blob", "commit", "tree")) {
                throw GitHubApiException("GitHub 트리 객체 형식을 확인할 수 없습니다.")
            }
            GitRevision.parse(it.sha)
        }
        return GitEvidenceSnapshot(ref, tree.tree.map { GitTreeEntry(it.path, it.mode, it.type, it.sha) }.associateBy { it.path })
    }

    override fun blob(repository: GitHubRepository, sha: String): ByteArray {
        val blob = get(repository, "/git/blobs/${GitRevision.parse(sha).value}", BlobResponse::class.java)
        if (blob.sha != sha || blob.encoding != "base64" || blob.size !in 0..MAX_BLOB_SIZE) {
            throw GitHubApiException("GitHub 코드 파일 형식 또는 크기를 확인할 수 없습니다.")
        }
        val bytes = try { Base64.getMimeDecoder().decode(blob.content) } catch (_: IllegalArgumentException) {
            throw GitHubApiException("GitHub 코드 파일 인코딩을 확인할 수 없습니다.")
        }
        if (bytes.size != blob.size) throw GitHubApiException("GitHub 코드 파일 크기가 일치하지 않습니다.")
        return bytes
    }

    override fun isAncestor(repository: GitHubRepository, ancestor: String, descendant: String): Boolean {
        if (ancestor == descendant) return true
        val result = get(repository, "/compare/${GitRevision.parse(ancestor).value}...${GitRevision.parse(descendant).value}?per_page=1", CompareResponse::class.java)
        return result.status == "ahead" || result.status == "identical"
    }

    private fun <T> get(repository: GitHubRepository, suffix: String, type: Class<T>): T = try {
        client.get().uri("/repos/${repository.key}$suffix")
            .headers { it.setBearerAuth(session.require().accessToken) }
            .exchange { _, response ->
                if (response.statusCode.value() == 401) throw GitHubUserAuthenticationException()
                if (!response.statusCode.is2xxSuccessful) throw GitHubApiException("GitHub 코드 조회 실패. HTTP ${response.statusCode.value()}")
                val bytes = response.body.readNBytes(MAX_RESPONSE_SIZE + 1)
                if (bytes.size > MAX_RESPONSE_SIZE) throw GitHubApiException("GitHub 코드 응답이 허용 크기를 초과했습니다.")
                try { mapper.readValue(bytes, type) } catch (_: RuntimeException) {
                    throw GitHubApiException("GitHub 코드 응답을 해석할 수 없습니다.")
                }
            } ?: throw GitHubApiException("GitHub 코드 응답이 비어 있습니다.")
    } catch (_: RestClientException) {
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
