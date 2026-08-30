package com.codingpit.muviss.ios

import com.codingpit.muviss.core.common.widget.WidgetRefresher

/**
 * The iOS half of the [WidgetRefresher] seam: asks WidgetKit to rebuild every
 * placed widget's timeline.
 *
 * Unlike Android's `GlanceWidgetRefresher`, this cannot make the call itself.
 * `WidgetCenter` is Swift-only — WidgetKit has no Objective-C surface at all,
 * so there is nothing for Kotlin/Native's cinterop to bind to, and no amount
 * of `.def` file makes it reachable. The reload is therefore supplied as
 * [reloadTimelines] by the Swift `AppDelegate`, which is where WidgetKit
 * lives anyway; Kotlin keeps the seam and the decision of *when* to reload.
 *
 * Thinner than the Android side by nature: WidgetKit owns when a widget is
 * actually redrawn — a reload is a request the system may coalesce or defer
 * under its own budget — so there is nothing here to clear or to await. The
 * Undo affordance the Android widget keeps in widget state is, on this side,
 * whatever the timeline entry says, and every reload rebuilds those from
 * scratch.
 *
 * Installed by [IosAppStartup.start], and deliberately not inside the widget
 * extension: a widget asking WidgetKit to reload the widget it is drawing
 * would be a loop.
 */
class IosWidgetRefresher(private val reloadTimelines: () -> Unit) : WidgetRefresher {
    override suspend fun refresh() {
        reloadTimelines()
    }
}
