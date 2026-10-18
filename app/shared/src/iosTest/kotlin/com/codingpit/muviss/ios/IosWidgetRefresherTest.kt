package com.codingpit.muviss.ios

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosWidgetRefresherTest {

    // A throwaway App Group-style suite per test, so the real widget marker is never touched.
    private val suite = "muviss.test.${NSUUID().UUIDString()}"
    private val defaults = NSUserDefaults(suiteName = suite)
    private val marker = WidgetUndoMarker(defaults)

    @AfterTest
    fun remove_the_test_suite() {
        NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suite)
    }

    @Test
    fun marker_round_trips_a_ticked_episode_id() {
        marker.markJustTicked("550/1/2")

        assertEquals("550/1/2", marker.justTicked())
    }

    @Test
    fun clear_removes_the_marker() {
        marker.markJustTicked("550/1/2")

        marker.clear()

        assertNull(marker.justTicked())
    }

    @Test
    fun refresh_clears_a_pending_undo_marker_and_reloads_once() {
        marker.markJustTicked("550/1/2")
        var reloads = 0
        val refresher = IosWidgetRefresher(undoMarker = marker, reloadTimelines = { reloads++ })

        runBlocking { refresher.refresh() }

        assertNull(marker.justTicked())
        assertEquals(1, reloads)
    }

    @Test
    fun refresh_still_clears_the_marker_when_the_reload_throws() {
        marker.markJustTicked("550/1/2")
        val refresher = IosWidgetRefresher(
            undoMarker = marker,
            reloadTimelines = { error("WidgetKit unavailable") },
        )

        runBlocking { refresher.refresh() }

        assertNull(marker.justTicked())
    }

    @Test
    fun marker_without_a_suite_is_a_no_op_rather_than_a_crash() {
        val absent = WidgetUndoMarker(defaults = null)

        absent.markJustTicked("550/1/2")
        absent.clear()

        assertNull(absent.justTicked())
    }
}
