package com.codingpit.muviss.ios

import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WidgetUndoMarkerTest {

    @Test
    fun open_suite_returns_a_usable_suite_for_an_ordinary_name() {
        val suite = WidgetUndoMarker.openSuite("muviss.test.${NSUUID().UUIDString()}")

        assertNotNull(suite)
    }

    // NSGlobalDomain is a suite Foundation refuses: NSUserDefaults(suiteName:) throws
    // NullPointerException for it on the simulator (probed; the bundle id is null in the test host,
    // so it cannot be used). openSuite must turn that throw into null.
    @Test
    fun open_suite_returns_null_for_a_suite_the_system_refuses() {
        assertNull(WidgetUndoMarker.openSuite(REFUSED_SUITE))
    }

    @Test
    fun marker_over_a_refused_suite_offers_no_undo_and_ignores_writes() {
        val marker = WidgetUndoMarker(WidgetUndoMarker.openSuite(REFUSED_SUITE))

        marker.markJustTicked("550/1/2")
        marker.clear()

        assertNull(marker.justTicked())
    }

    private companion object {
        const val REFUSED_SUITE = "NSGlobalDomain"
    }
}
