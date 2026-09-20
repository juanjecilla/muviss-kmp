package com.codingpit.muviss.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SyncScheduleControllerTest {

    private class RecordingScheduler : BackgroundSyncScheduler {
        val calls = mutableListOf<String>()
        override fun schedule() {
            calls += "schedule"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    @Test
    fun it_schedules_when_the_policy_is_on_at_start() = runTest {
        val scheduler = RecordingScheduler()
        val enabled = MutableStateFlow(true)
        backgroundScope.launch { SyncScheduleController(scheduler, enabled).run() }
        runCurrent()

        assertEquals(listOf("schedule"), scheduler.calls)
    }

    @Test
    fun it_cancels_when_the_policy_is_off_at_start_so_a_stale_job_cannot_outlive_the_switch() = runTest {
        // A job scheduled in an earlier session survives a process death; if
        // the switch was turned off since, nothing else would remove it.
        val scheduler = RecordingScheduler()
        backgroundScope.launch { SyncScheduleController(scheduler, MutableStateFlow(false)).run() }
        runCurrent()

        assertEquals(listOf("cancel"), scheduler.calls)
    }

    @Test
    fun it_follows_the_policy_as_it_flips() = runTest {
        val scheduler = RecordingScheduler()
        val enabled = MutableStateFlow(false)
        backgroundScope.launch { SyncScheduleController(scheduler, enabled).run() }
        runCurrent()

        enabled.value = true
        runCurrent()
        enabled.value = false
        runCurrent()
        enabled.value = true
        runCurrent()

        assertEquals(listOf("cancel", "schedule", "cancel", "schedule"), scheduler.calls)
    }

    @Test
    fun a_repeated_value_does_not_reschedule() = runTest {
        val scheduler = RecordingScheduler()
        val enabled = MutableStateFlow(true)
        backgroundScope.launch { SyncScheduleController(scheduler, enabled).run() }
        runCurrent()

        enabled.value = true
        runCurrent()

        assertEquals(listOf("schedule"), scheduler.calls)
    }
}
