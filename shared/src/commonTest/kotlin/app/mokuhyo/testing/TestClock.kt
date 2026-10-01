package app.mokuhyo.testing

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** A clock tests move by hand. */
class TestClock(var now: Instant = Instant.parse("2026-09-01T09:00:00Z")) : Clock {
    override fun now(): Instant = now
    fun advance(by: Duration) {
        now += by
    }
}
