package com.codingpit.muviss.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.codingpit.muviss.core.common.widget.WidgetRefresher

/**
 * The Android half of the [WidgetRefresher] seam: redraws every placed
 * "Watch next" widget.
 *
 * Clearing the Undo marker is part of a refresh rather than a separate
 * concern. A refresh means something changed that the widget did not do
 * itself — a tick in the app, the daily reload, a new episode airing — and an
 * Undo button left over from an earlier interaction would then be offering to
 * take back a write the person has already moved past.
 *
 * Failures are swallowed deliberately. This runs inside the transaction's
 * caller, and a home screen that failed to redraw must never turn into a tick
 * that failed to save.
 */
class GlanceWidgetRefresher(private val context: Context) : WidgetRefresher {

    override suspend fun refresh() {
        runCatching {
            val widget = MuvissWidget()
            GlanceAppWidgetManager(context).getGlanceIds(MuvissWidget::class.java).forEach { id ->
                updateAppWidgetState(context, id) { it.remove(JustTickedKey) }
                widget.update(context, id)
            }
        }
    }
}
