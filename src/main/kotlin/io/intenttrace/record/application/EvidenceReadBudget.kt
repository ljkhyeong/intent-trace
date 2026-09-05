package io.intenttrace.record.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

enum class HistoryStopReason { TIME_LIMIT, CALL_LIMIT, CANCELLED }
class EvidenceReadStopped(val reason: HistoryStopReason) : RuntimeException("코드 조회를 중단했습니다: ${reason.name}")

// 요청마다 새로 만든다. 원격 응답과 후보 처리 사이에서 같은 기한을 확인한다.
class EvidenceReadBudget(
    private val timeLimit: Duration,
    private val maxRemoteCalls: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val started = nanoTime()
    var remoteCalls: Int = 0
        private set

    init {
        require(!timeLimit.isNegative && !timeLimit.isZero && timeLimit <= Duration.ofSeconds(40))
        require(maxRemoteCalls in 1..200)
    }

    fun checkpoint() {
        if (Thread.currentThread().isInterrupted) throw EvidenceReadStopped(HistoryStopReason.CANCELLED)
        if (nanoTime() - started >= timeLimit.toNanos()) throw EvidenceReadStopped(HistoryStopReason.TIME_LIMIT)
    }

    fun beforeRemoteCall(): Duration {
        checkpoint()
        if (remoteCalls >= maxRemoteCalls) throw EvidenceReadStopped(HistoryStopReason.CALL_LIMIT)
        remoteCalls++
        return Duration.ofMillis(((timeLimit.toNanos() - (nanoTime() - started) + 999_999) / 1_000_000).coerceAtLeast(1))
    }
}

@Component
class HistoryReadPolicy(
    @Value("\${intent-trace.history.time-limit:30s}") private val timeLimit: Duration,
    @Value("\${intent-trace.history.max-remote-calls:40}") private val maxRemoteCalls: Int,
) {
    init {
        // 빈 캐시에서도 원본·대상 커밋과 트리 4회, 조상 비교 1회, 두 blob 2회를 읽을 수 있어야 한다.
        require(maxRemoteCalls in 7..200) { "intent-trace.history.max-remote-calls는 코드 근거 하나를 확인할 수 있도록 7~200으로 설정해 주세요." }
        EvidenceReadBudget(timeLimit, maxRemoteCalls)
    }
    fun start() = EvidenceReadBudget(timeLimit, maxRemoteCalls)
}
