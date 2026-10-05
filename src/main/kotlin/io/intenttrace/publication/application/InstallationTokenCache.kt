package io.intenttrace.publication.application

/** 게시용 설치 토큰의 메모리 캐시다. 설치 변경 웹훅을 받으면 해당 설치 ID로 발급한 토큰만 버린다. */
interface InstallationTokenCache {
    fun evictInstallation(installationId: Long): Int
}
