package com.codingpit.muviss.ios

import com.codingpit.muviss.core.common.widget.WidgetRefresher

/**
 * The iOS half of the [WidgetRefresher] seam, and the counterpart of Android's
 * `GlanceWidgetRefresher`: a refresh clears the widget's Undo marker, then asks
 * WidgetKit to rebuild every placed widget's timeline.
 *
 * The clear is part of a refresh for the same reason it is on Android. A refresh
 * means something changed that the widget did not do itself (a tick in the app,
 * the daily reload, a new episode), so an Undo button left from an earlier
 * interaction would offer to take back a write the person has moved past.
 *
 * The reload cannot be made from Kotlin: `WidgetCenter` is Swift-only, WidgetKit
 * has no Objective-C surface, so [reloadTimelines] is supplied by the Swift
 * `AppDelegate`. Kotlin keeps the seam and the decision of *when* to reload.
 *
 * Installed by [IosAppStartup.start], and deliberately not inside the widget
 * extension. The extension writes through the same repositories, but nothing
 * is installed there, so a widget tick never clears its own marker, and the
 * extension never asks WidgetKit to reload the widget it is drawing.
 *
 * Failures are swallowed for the reason `GlanceWidgetRefresher` gives: this runs
 * inside a write's caller, and a home screen that failed to redraw must never
 * turn a saved tick into an error.
 */
class IosWidgetRefresher(
    private val undoMarker: WidgetUndoMarker = WidgetUndoMarker.appGroup(),
    private val reloadTimelines: () -> Unit,
) : WidgetRefresher {

    override suspend fun refresh() {
        runCatching { undoMarker.clear() }
        runCatching { reloadTimelines() }
    }
}
