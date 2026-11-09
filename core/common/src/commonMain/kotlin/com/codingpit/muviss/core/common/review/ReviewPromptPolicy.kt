package com.codingpit.muviss.core.common.review

/**
 * What the review prompt's guardrails read, per device (#410). Every field describes how this
 * device's app has been used, never the library, so none of it syncs or appears in the export.
 */
data class ReviewRecord(
    /** When this device first launched the app with the ledger present; null until stamped, which reads as "not yet eligible". */
    val installedAtEpochMs: Long?,
    /** In-app ticks made by hand so far: episodes and movies the person ticked, not imports, sync pulls or widgets. */
    val tickCount: Int,
    val lastAskedAtEpochMs: Long?,
    val lastAskedVersion: String?,
)

/**
 * When the app may ask the store for a review (#410, the owner's rules).
 *
 * A request is allowed only when every one of these holds:
 * - the install is stamped and at least [MIN_INSTALL_DAYS] old;
 * - at least [MIN_TICKS] in-app ticks have been made;
 * - the last ask, if there was one, is at least [COOLDOWN_DAYS] old;
 * - this app version has not already been asked for;
 * - nothing has failed and is still standing (a sync failure, see `ReviewPromptOnCompletion`).
 *
 * Pure on purpose: every input is a parameter, so the boundaries are tested without a clock,
 * a database or a store. A clock that reads earlier than the last ask fails the cooldown, which
 * is the conservative answer when the clock has gone backwards.
 */
object ReviewPromptPolicy {
    const val MIN_INSTALL_DAYS = 14
    const val MIN_TICKS = 20
    const val COOLDOWN_DAYS = 90

    private const val MILLIS_PER_DAY = 86_400_000L

    fun mayAsk(record: ReviewRecord, nowEpochMs: Long, currentVersion: String, recentlyFailed: Boolean): Boolean {
        if (recentlyFailed) return false
        val installedAt = record.installedAtEpochMs ?: return false
        if (nowEpochMs - installedAt < MIN_INSTALL_DAYS * MILLIS_PER_DAY) return false
        if (record.tickCount < MIN_TICKS) return false
        val lastAskedAt = record.lastAskedAtEpochMs
        if (lastAskedAt != null && nowEpochMs - lastAskedAt < COOLDOWN_DAYS * MILLIS_PER_DAY) return false
        return record.lastAskedVersion != currentVersion
    }
}
