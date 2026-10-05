package io.intenttrace.publication.application

import org.springframework.stereotype.Service

/** 게시용 설치 토큰의 메모리 캐시다. 설치 ID로 발급한 토큰만 버린다. */
interface InstallationTokenCache {
    fun evictInstallation(installationId: Long): Int
}

@Service
class GitHubInstallationWebhookService(private val tokens: InstallationTokenCache) {
    /** 설치 제거·정지·권한 수락·저장소 범위 변경 뒤 이전 범위의 토큰을 다시 쓰지 않는다. */
    fun installationChanged(installationId: Long): Int = tokens.evictInstallation(installationId)
}
