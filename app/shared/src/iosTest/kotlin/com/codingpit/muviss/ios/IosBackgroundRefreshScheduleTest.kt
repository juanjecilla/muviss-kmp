package com.codingpit.muviss.ios

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosBackgroundRefreshScheduleTest {

    private val taskId = "com.codingpit.muviss.refresh"

    @Test
    fun launch_keeps_a_pending_request_for_the_same_task() {
        val submit = IosBackgroundRefreshSchedule.shouldSubmit(
            pendingIdentifiers = listOf(taskId),
            taskId = taskId,
            keepExisting = true,
        )

        assertFalse(submit)
    }

    @Test
    fun launch_submits_when_nothing_is_pending() {
        val submit = IosBackgroundRefreshSchedule.shouldSubmit(
            pendingIdentifiers = emptyList(),
            taskId = taskId,
            keepExisting = true,
        )

        assertTrue(submit)
    }

    @Test
    fun launch_submits_when_only_other_tasks_are_pending() {
        val submit = IosBackgroundRefreshSchedule.shouldSubmit(
            pendingIdentifiers = listOf("com.codingpit.muviss.other"),
            taskId = taskId,
            keepExisting = true,
        )

        assertTrue(submit)
    }

    @Test
    fun after_a_run_always_submits_even_with_a_stale_pending_entry() {
        val submit = IosBackgroundRefreshSchedule.shouldSubmit(
            pendingIdentifiers = listOf(taskId),
            taskId = taskId,
            keepExisting = false,
        )

        assertTrue(submit)
    }

    @Test
    fun repeated_launches_never_push_a_pending_run_further_out() {
        // A launch that finds its own request pending must submit nothing, however many launches happen.
        val pendingAfterFirstLaunch = listOf(taskId)

        repeat(5) {
            assertFalse(
                IosBackgroundRefreshSchedule.shouldSubmit(
                    pendingIdentifiers = pendingAfterFirstLaunch,
                    taskId = taskId,
                    keepExisting = true,
                ),
            )
        }
    }
}
