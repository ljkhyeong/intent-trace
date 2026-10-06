package io.intenttrace.record.application

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

enum class HistoryStopReason { TIME_LIMIT, CALL_LIMIT, CANCELLED }
class EvidenceReadStopped(val reason: HistoryStopReason) : RuntimeException("코드 조회를 중단했습니다: ${reason.name}")

// 요청마다 새로 만든다. 원격 응답과 후보 처리 사이에서 같은 기한을 확인한다.
class EvidenceReadBudget(
    private val timeLimit: Duration,
    private val maxRemoteCalls: Int,
) {
    private val started = System.nanoTime()
    var remoteCalls: Int = 0
        private set

    fun checkpoint() {
        if (Thread.currentThread().isInterrupted) throw EvidenceReadStopped(HistoryStopReason.CANCELLED)
        if (System.nanoTime() - started >= timeLimit.toNanos()) throw EvidenceReadStopped(HistoryStopReason.TIME_LIMIT)
    }

    fun beforeRemoteCall(): Duration {
        checkpoint()
        if (remoteCalls >= maxRemoteCalls) throw EvidenceReadStopped(HistoryStopReason.CALL_LIMIT)
        remoteCalls++
        // 읽기 시간 제한은 밀리초 미만을 버리므로 0ms가 되지 않게 최소 1ms로 둔다.
        return timeLimit.minusNanos(System.nanoTime() - started).coerceAtLeast(Duration.ofMillis(1))
    }
}

@ConfigurationProperties("intent-trace.history")
class HistoryReadPolicy(
    private val timeLimit: Duration = Duration.ofSeconds(30),
    private val maxRemoteCalls: Int = 40,
) {
    init {
        require(timeLimit.isPositive && timeLimit <= Duration.ofSeconds(40)) { "intent-trace.history.time-limit은 0보다 크고 40초 이하로 설정해 주세요." }
        // 빈 캐시에서도 원본·대상 커밋과 트리 4회, 조상 비교 1회, 두 blob 2회를 읽을 수 있어야 한다.
        require(maxRemoteCalls in 7..200) { "intent-trace.history.max-remote-calls는 코드 근거 하나를 확인할 수 있도록 7~200으로 설정해 주세요." }
    }
    fun start() = EvidenceReadBudget(timeLimit, maxRemoteCalls)
}
