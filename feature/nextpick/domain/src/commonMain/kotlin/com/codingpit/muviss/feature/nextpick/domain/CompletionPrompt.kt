package com.codingpit.muviss.feature.nextpick.domain

import kotlinx.coroutines.flow.Flow

/**
 * Whether the "What's next?" sheet may appear now (EPIC 51, #400): after a Completion the person made
 * in the app, at most once a day, never before onboarding is done, and only while the Settings switch
 * (#403, [CompletionPromptStore.enabled]) is on. Pure, so every rule is testable without a screen.
 *
 * A "day" is a UTC day here: the stored timestamp is epoch milliseconds, and the boundary only has to
 * be consistent, not local midnight.
 */
object CompletionPromptPolicy {

    const val DAY_MS: Long = 86_400_000L

    fun shouldShow(
        nowEpochMs: Long,
        lastShownEpochMs: Long?,
        enabled: Boolean,
        onboardingCompleted: Boolean,
    ): Boolean {
        if (!enabled || !onboardingCompleted) return false
        val last = lastShownEpochMs ?: return true
        return dayOf(nowEpochMs) > dayOf(last)
    }

    private fun dayOf(epochMs: Long): Long = epochMs.floorDiv(DAY_MS)
}

/**
 * The per-device memory of the completion sheet. [markShown] is written **when the sheet appears**, not
 * when the person answers it, so "Not now" and a dismissal by swiping both count as the day's prompt.
 * Per device and never synced or exported: a prompt is a fact about this phone's day, not the library.
 */
interface CompletionPromptStore {
    /** The Settings switch (#403). Until #403 lands the binding is always on. */
    val enabled: Flow<Boolean>

    /** When the sheet was last shown on this device, or null if it never was. */
    suspend fun lastShownAtEpochMs(): Long?

    suspend fun markShown(atEpochMs: Long)
}
