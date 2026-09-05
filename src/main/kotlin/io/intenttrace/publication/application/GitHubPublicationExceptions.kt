package io.intenttrace.publication.application

class ForkPullRequestUnsupportedException : RuntimeException("Fork 저장소에서 만든 Pull Request 게시는 지원하지 않습니다.")

class PullRequestRevisionMismatchException(recordRevision: String, pullRequestRevision: String) :
    RuntimeException("변경 기록 커밋 $recordRevision 과 Pull Request HEAD $pullRequestRevision 이 일치하지 않습니다.")

class GitHubRepositoryMismatchException(recordRepository: String, targetRepository: String) :
    RuntimeException("변경 기록 저장소 $recordRepository 와 GitHub 저장소 $targetRepository 가 일치하지 않습니다.")

class GitHubCredentialMissingException :
    RuntimeException("GitHub 고정 token 또는 GitHub App 자격 증명이 설정되지 않았습니다.")

class GitHubCredentialConfigurationException :
    RuntimeException("GitHub App client ID 또는 private key 설정이 올바르지 않습니다.")

class GitHubPublicationContentTooLargeException :
    RuntimeException("GitHub Check Run에 게시할 Markdown이 65,535자를 초과합니다.")

class GitHubApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
