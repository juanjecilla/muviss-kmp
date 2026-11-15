package com.codingpit.muviss.ios

import com.codingpit.muviss.core.database.MUVISS_APP_GROUP
import platform.Foundation.NSUserDefaults

/**
 * The "just ticked, offer Undo" marker on iOS (Android keeps the same thing in
 * Glance's per-widget state).
 *
 * It lives in the App Group's defaults, the container the database already
 * shares between the app and the widget extension (ADR 0016). That is what lets
 * the two sides agree on one value: the extension sets it when the person ticks
 * from the widget, and the app clears it on every refresh (see
 * [IosWidgetRefresher]). The key is read by nothing else, so renaming it only
 * needs this file changed.
 *
 * [defaults] is nullable because a suite the system refuses (see [openSuite])
 * must degrade to "no Undo button", never to a crash on the home screen.
 */
class WidgetUndoMarker(private val defaults: NSUserDefaults?) {

    fun justTicked(): String? = defaults?.stringForKey(KEY)

    fun markJustTicked(episodeId: String) {
        defaults?.setObject(episodeId, forKey = KEY)
    }

    fun clear() {
        defaults?.removeObjectForKey(KEY)
    }

    companion object {
        /** The same key `WidgetUndoStore` used before this moved to Kotlin, so an existing marker still reads. */
        internal const val KEY = "justTickedEpisodeId"

        /** The marker in the App Group both processes share. */
        fun appGroup(): WidgetUndoMarker = WidgetUndoMarker(openSuite(MUVISS_APP_GROUP))

        /**
         * Opens a defaults suite, or returns null when the system refuses it.
         *
         * `NSUserDefaults(suiteName:)` is a failable Objective-C initializer, and Kotlin/Native
         * types it as non-null: when the system refuses the suite the call throws
         * `NullPointerException` rather than yielding null, so an elvis after it can never run.
         * Catching is the only way to turn a refusal into the null this marker is built around.
         */
        internal fun openSuite(suiteName: String): NSUserDefaults? = runCatching { NSUserDefaults(suiteName = suiteName) }.getOrNull()
    }
}
