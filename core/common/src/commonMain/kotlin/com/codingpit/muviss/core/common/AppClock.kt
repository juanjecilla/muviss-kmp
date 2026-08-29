package com.codingpit.muviss.core.common

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Wall-clock time, abstracted so it can be faked in tests (e.g. asserting
 * `addedAtEpochMs`/`updatedAtEpochMs` on a saved [collection entry]
 * [com.codingpit.muviss.feature.collection.domain.CollectionEntry]).
 */
interface AppClock {
    fun nowEpochMs(): Long
}

@OptIn(ExperimentalTime::class)
class SystemClock : AppClock {
    override fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()
}

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * Today as an epoch day (days since 1970-01-01 UTC), matching the convention
 * used by [com.codingpit.muviss.models.Episode.airDateEpochDay] for "has this
 * episode aired yet" comparisons.
 */
fun AppClock.todayEpochDay(): Long = nowEpochMs() / MILLIS_PER_DAY

/**
 * Converts an arbitrary wall-clock timestamp (epoch milliseconds) to the same
 * epoch-day convention as [todayEpochDay] — used to bucket per-tick
 * `updatedAtEpochMs` timestamps (e.g. `episodeProgress` rows) into calendar
 * days for streak calculations (see the profile feature's `WatchStreakCalculator`).
 */
fun epochDayOf(epochMs: Long): Long = epochMs / MILLIS_PER_DAY

/**
 * The inverse of [epochDayOf]: the first millisecond of [epochDay], UTC.
 *
 * Turns a calendar boundary back into the bound a timestamp query wants —
 * "everything since the start of this year" is an epoch day computed with
 * [epochDayOfCivil] and then widened here.
 */
fun epochMsAtStartOfDay(epochDay: Long): Long = epochDay * MILLIS_PER_DAY
