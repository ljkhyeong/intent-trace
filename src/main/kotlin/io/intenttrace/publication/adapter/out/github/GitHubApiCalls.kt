package io.intenttrace.publication.adapter.out.github

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.intenttrace.config.GitHubApiException
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.publication.application.GitHubRepositoryMismatchException
import io.intenttrace.publication.application.PullRequestSnapshot
import io.intenttrace.publication.domain.GitHubPullRequestTarget
import io.intenttrace.record.domain.requireFullRevision
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

/** 게시 어댑터의 RestClient 오류를 원문 없이 GitHubApiException으로 바꾼다. */
internal fun <T> safeCall(operation: String, ifNotFound: (() -> T)? = null, call: () -> T): T {
    try {
        return call()
    } catch (exception: RestClientResponseException) {
        if (ifNotFound != null && exception.statusCode == HttpStatus.NOT_FOUND) {
            return ifNotFound()
        }
        throw GitHubApiException("GitHub $operation 요청이 실패했습니다. HTTP ${exception.statusCode.value()}")
    } catch (_: RestClientException) {
        throw GitHubApiException("GitHub $operation 요청을 완료하지 못했습니다.")
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PullRequestResponse(val head: PullRequestRef, val base: PullRequestRef)

@JsonIgnoreProperties(ignoreUnknown = true)
// base에는 sha가 필요 없다. head의 빈 sha는 커밋 형식 오류로 처리한다.
internal data class PullRequestRef(val sha: String = "", val repo: PullRequestRepository? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PullRequestRepository(val id: Long, @JsonProperty("full_name") val fullName: String)

/** PR이 요청한 저장소로 병합되는지 확인한다. head 저장소가 다르거나 삭제된 Fork이면 [PullRequestSnapshot.fork]다. */
internal fun PullRequestResponse.toSnapshot(target: GitHubPullRequestTarget): PullRequestSnapshot = try {
    val baseRepository = base.repo ?: throw GitHubApiException("GitHub PR의 base 저장소를 확인할 수 없습니다.")
    if (baseRepository.id <= 0) throw GitHubApiException("GitHub PR의 저장소 ID가 올바르지 않습니다.")
    val baseKey = GitHubRepository.parse(baseRepository.fullName).key
    if (baseKey != target.repositoryKey) throw GitHubRepositoryMismatchException(target.repositoryKey, baseKey)
    val headRepository = head.repo
    PullRequestSnapshot(requireFullRevision(head.sha),
        headRepository == null || headRepository.id != baseRepository.id || GitHubRepository.parse(headRepository.fullName).key != baseKey)
} catch (_: IllegalArgumentException) {
    throw GitHubApiException("GitHub PR 응답 형식이 올바르지 않습니다.")
}
