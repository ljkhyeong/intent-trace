package io.intenttrace

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(private var current: Instant) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
