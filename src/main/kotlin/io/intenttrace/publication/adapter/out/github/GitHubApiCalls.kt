package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubApiException
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
