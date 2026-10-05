package io.intenttrace.publication.application

import io.intenttrace.config.GitHubApiException

class ForkPullRequestUnsupportedException : RuntimeException("Fork PR에는 IntentTrace Check Run을 게시할 수 없습니다.")

class PullRequestRevisionMismatchException(recordRevision: String, pullRequestRevision: String) :
    RuntimeException("기록의 커밋($recordRevision)과 PR HEAD($pullRequestRevision)가 다릅니다.")

class GitHubRepositoryMismatchException(recordRepository: String, targetRepository: String) :
    RuntimeException("기록의 저장소($recordRepository)와 GitHub 게시 대상($targetRepository)이 다릅니다.")

class GitHubCredentialMissingException :
    RuntimeException("GitHub 게시용 고정 토큰 또는 GitHub App 인증이 설정되지 않았습니다.")

class GitHubCredentialConfigurationException :
    RuntimeException("GitHub App client ID 또는 private key 설정이 올바르지 않습니다.")

class GitHubPublicationContentTooLargeException :
    RuntimeException("GitHub Check Run에 게시할 Markdown이 65,535자를 초과합니다.")

/** Check Run을 쓰기 전에 PR을 읽지 못한 실패다. 원격 결과를 다시 확인할 필요가 없다. */
class PullRequestUnavailableException(failure: GitHubApiException) :
    GitHubApiException("${failure.message} GitHub에 쓰기 전에 중단했습니다. 저장소·PR 번호와 GitHub App 설치를 확인하세요.")
