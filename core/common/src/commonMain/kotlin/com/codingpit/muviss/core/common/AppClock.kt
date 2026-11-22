package com.codingpit.muviss.core.common

import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Wall-clock time, abstracted so it can be faked in tests (e.g. asserting
 * `addedAtEpochMs`/`updatedAtEpochMs` on a saved [collection entry]
 * [com.codingpit.muviss.feature.collection.domain.CollectionEntry]).
 */
interface AppClock {
    fun nowEpochMs(): Long

    /**
     * The UTC offset of the person's time zone at [epochMs], in milliseconds, positive east of UTC.
     *
     * It is per instant, not "the current offset": a stored timestamp and `now` can sit on opposite sides
     * of a DST change. The default is UTC so a fake never reads the host machine's zone, which would make
     * a test's calendar day depend on where it runs. [SystemClock] overrides it with the device's zone.
     */
    fun utcOffsetMsAt(epochMs: Long): Long = 0L
}

@OptIn(ExperimentalTime::class)
class SystemClock : AppClock {
    override fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()

    override fun utcOffsetMsAt(epochMs: Long): Long = TimeZone.currentSystemDefault().offsetAt(Instant.fromEpochMilliseconds(epochMs)).totalSeconds * 1_000L
}

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * Today as an epoch day (days since 1970-01-01 **UTC**), matching the convention
 * used by [com.codingpit.muviss.models.Episode.airDateEpochDay] for "has this
 * episode aired yet" comparisons.
 *
 * UTC on purpose, and not a bug to fix in one call site. The aired count stored on a
 * `CollectionEntry` (`CivilDate`/`airedEpisodeCount(todayEpochDay())`) and the
 * `seenEpisodes <= airedEpisodes` invariant in `WatchProgress` both depend on this
 * day. A caller that switched to [localTodayEpochDay] alone would tick an episode the
 * stored count does not include, and the Library would crash on the next read. Aired
 * comparisons move together, or not at all. The follow-up issue lists the whole set.
 */
fun AppClock.todayEpochDay(): Long = nowEpochMs() / MILLIS_PER_DAY

/**
 * Today in the person's time zone, as an epoch day. Used for Snooze due days and the
 * once-a-day What's next? rule, so that "today" starts at the person's midnight.
 *
 * Not for aired comparisons; see [todayEpochDay]. Stored due days are plain day numbers
 * with no zone attached, so a due day written before this rule existed is at most one
 * day off, and only for a Snooze in flight at the upgrade.
 */
fun AppClock.localTodayEpochDay(): Long = localEpochDayOf(nowEpochMs())

/** The epoch day of [epochMs] in the person's time zone (see [localTodayEpochDay]). */
fun AppClock.localEpochDayOf(epochMs: Long): Long = (epochMs + utcOffsetMsAt(epochMs)).floorDiv(MILLIS_PER_DAY)

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
