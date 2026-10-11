package com.codingpit.muviss.feature.profile.domain

/**
 * How long ago a sync last completed, as a value rather than a sentence: the
 * wording ("Synced 3m ago", "Sincronizado hace 3 min") is the UI's, because
 * only `:feature:profile:ui` has Compose Resources (#217).
 */
sealed interface LastSynced {
    data object Never : LastSynced

    data object JustNow : LastSynced

    data class MinutesAgo(val minutes: Long) : LastSynced

    data class HoursAgo(val hours: Long) : LastSynced

    data class DaysAgo(val days: Long) : LastSynced
}

/**
 * Buckets [lastSyncedAtEpochMs] relative to [nowEpochMs]. No date library —
 * same "plain epoch arithmetic, must run on every target" rationale as
 * `feature/progress/domain`'s `upcomingDateLabel`.
 */
fun lastSynced(lastSyncedAtEpochMs: Long?, nowEpochMs: Long): LastSynced {
    if (lastSyncedAtEpochMs == null) return LastSynced.Never
    val deltaMs = (nowEpochMs - lastSyncedAtEpochMs).coerceAtLeast(0)
    val minutes = deltaMs / MILLIS_PER_MINUTE
    val hours = deltaMs / MILLIS_PER_HOUR
    val days = deltaMs / MILLIS_PER_DAY
    return when {
        minutes < 1 -> LastSynced.JustNow
        minutes < MINUTES_PER_HOUR -> LastSynced.MinutesAgo(minutes)
        hours < HOURS_PER_DAY -> LastSynced.HoursAgo(hours)
        else -> LastSynced.DaysAgo(days)
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_DAY = 86_400_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
