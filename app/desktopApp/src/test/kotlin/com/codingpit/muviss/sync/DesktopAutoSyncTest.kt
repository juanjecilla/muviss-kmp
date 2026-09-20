package com.codingpit.muviss.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAutoSyncTest {

    @Test
    fun the_interval_is_fifteen_minutes() {
        assertEquals(15.minutes, DesktopAutoSync.INTERVAL)
    }

    @Test
    fun it_waits_a_full_interval_before_the_first_tick() = runTest {
        var ticks = 0
        backgroundScope.launch { DesktopAutoSync { ticks++ }.run() }
        runCurrent()

        advanceTimeBy(14.minutes)
        runCurrent()
        assertEquals(0, ticks, "opening the app is a foreground, which is not this timer's job")

        advanceTimeBy(1.minutes + 1.minutes)
        runCurrent()
        assertEquals(1, ticks)
    }

    @Test
    fun it_ticks_every_interval_while_the_window_is_open() = runTest {
        var ticks = 0
        backgroundScope.launch { DesktopAutoSync { ticks++ }.run() }
        runCurrent()

        advanceTimeBy(61.minutes)
        runCurrent()

        assertEquals(4, ticks)
    }

    @Test
    fun a_failing_tick_does_not_end_the_timer() = runTest {
        var ticks = 0
        backgroundScope.launch {
            DesktopAutoSync {
                ticks++
                error("network is down")
            }.run()
        }
        runCurrent()

        advanceTimeBy(31.minutes)
        runCurrent()

        assertEquals(2, ticks)
    }
}
