package com.codingpit.muviss.core.common.analytics

/**
 * One thing worth recording. Features declare their own events next to the
 * code that raises them, so this module never learns what a feature does.
 */
interface AnalyticsEvent {
    val name: String

    val properties: Map<String, String>
        get() = emptyMap()
}

/**
 * Seam for product analytics.
 *
 * [NoOpAnalyticsTracker] is the only implementation, and it is bound
 * unconditionally: Muviss ships no analytics vendor, has no consent flow, and
 * `docs/PRIVACY.md` states that it collects nothing. Call sites exist so that
 * adding a vendor later is a binding change rather than a hunt for the right
 * places to instrument — the same reason ADR 0002 put a change-log on every
 * table years before a sync backend existed.
 *
 * Until a vendor is chosen, treat every [track] call as documentation of what
 * *would* be measured, not as a measurement.
 */
interface AnalyticsTracker {
    fun track(event: AnalyticsEvent)
}

/** Drops everything, successfully — mirrors `NoOpSyncBackend`'s contract. */
object NoOpAnalyticsTracker : AnalyticsTracker {
    override fun track(event: AnalyticsEvent) = Unit
}
