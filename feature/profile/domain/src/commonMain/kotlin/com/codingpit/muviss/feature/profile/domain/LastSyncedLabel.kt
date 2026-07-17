package com.codingpit.muviss.feature.profile.domain

/**
 * Human label for how long ago a sync last completed, relative to
 * [nowEpochMs]. No date library — same "plain epoch arithmetic, must run on
 * every target" rationale as `feature/progress/domain`'s `upcomingDateLabel`.
 */
fun lastSyncedLabel(lastSyncedAtEpochMs: Long?, nowEpochMs: Long): String {
    if (lastSyncedAtEpochMs == null) return "Never synced"
    val deltaMs = (nowEpochMs - lastSyncedAtEpochMs).coerceAtLeast(0)
    val minutes = deltaMs / MILLIS_PER_MINUTE
    val hours = deltaMs / MILLIS_PER_HOUR
    val days = deltaMs / MILLIS_PER_DAY
    return when {
        minutes < 1 -> "Synced just now"
        minutes < MINUTES_PER_HOUR -> "Synced ${minutes}m ago"
        hours < HOURS_PER_DAY -> "Synced ${hours}h ago"
        else -> "Synced ${days}d ago"
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_DAY = 86_400_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
