package com.codingpit.muviss.core.testing

import com.codingpit.muviss.core.common.AppClock

/**
 * An [AppClock] that reads whatever a test last set it to.
 *
 * Lives here because the test suites had grown thirty-seven copies of it —
 * fixed, settable, advanceable, under a dozen names — and every one of them
 * was this class (EPIC 38, #71).
 *
 * There is deliberately no default start time: several of the copies
 * defaulted to `0L` and others to a suite's own `NOW_EPOCH_MS`, so a default
 * here would silently move one half of them. A test that needs a calendar day
 * rather than an instant passes `epochMsAtStartOfDay(day)` from `:core:common`.
 */
class FakeClock(var epochMs: Long) : AppClock {
    override fun nowEpochMs(): Long = epochMs

    /** Moves the clock to [epochMs], forwards or backwards. */
    fun advanceTo(epochMs: Long) {
        this.epochMs = epochMs
    }

    /** Moves the clock forwards by [millis]. */
    fun advanceBy(millis: Long) {
        epochMs += millis
    }
}
