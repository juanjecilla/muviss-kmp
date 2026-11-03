package com.codingpit.muviss.feature.nextpick.domain

import kotlinx.coroutines.flow.Flow

/**
 * Whether the "What's next?" sheet may appear now (EPIC 51, #400): after a Completion the person made
 * in the app, at most once a day, never before onboarding is done, and only while the Settings switch
 * (#403, [CompletionPromptStore.enabled]) is on. Pure, so every rule is testable without a screen.
 *
 * A "day" is the person's local day, read through [localDayOf] (see `localEpochDayOf` in `:core:common`).
 * It used to be a UTC day, which let someone who finished a title at 23:50 local see the sheet again at
 * 00:10 local. [lastShownEpochMs] is an instant, so a value stored before this rule needs no migration:
 * it is bucketed under the local day like any other.
 */
object CompletionPromptPolicy {

    const val DAY_MS: Long = 86_400_000L

    fun shouldShow(
        nowEpochMs: Long,
        lastShownEpochMs: Long?,
        enabled: Boolean,
        onboardingCompleted: Boolean,
        localDayOf: (epochMs: Long) -> Long,
    ): Boolean {
        if (!enabled || !onboardingCompleted) return false
        val last = lastShownEpochMs ?: return true
        return localDayOf(nowEpochMs) > localDayOf(last)
    }
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
