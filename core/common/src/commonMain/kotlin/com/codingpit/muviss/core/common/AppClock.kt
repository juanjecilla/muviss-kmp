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
 * Today as an epoch day in the person's time zone (#675): the one day every "has this aired", "is it today",
 * "is it due" and "does this count toward the streak" check reads. It is the same basis as
 * [localTodayEpochDay], under the name the aired comparisons have always used.
 *
 * Air dates are civil dates (`Episode.airDateEpochDay`), so comparing them with the local day is the
 * comparison a person means: an episode dated today is aired at their midnight, not at UTC's. Every consumer
 * moves together, because a stored aired count and a tick written on different days would break the
 * `seen <= aired` invariant `WatchProgress` requires and the Library would crash on the next read. Anything
 * that buckets a timestamp into a day must use [localEpochDayOf] for the same reason.
 */
fun AppClock.todayEpochDay(): Long = localTodayEpochDay()

/**
 * Today in the person's time zone, as an epoch day: the same day as [todayEpochDay]. Named for the
 * snooze and once-a-day call sites that were written before the aired basis moved here (#656, #675).
 */
fun AppClock.localTodayEpochDay(): Long = localEpochDayOf(nowEpochMs())

/** The epoch day of [epochMs] in the person's time zone (see [todayEpochDay]). */
fun AppClock.localEpochDayOf(epochMs: Long): Long = (epochMs + utcOffsetMsAt(epochMs)).floorDiv(MILLIS_PER_DAY)

/**
 * Converts an arbitrary wall-clock timestamp (epoch milliseconds) to a UTC epoch day.
 *
 * Not the person's day: a day that counts toward a streak, an aired comparison or a
 * "today" is [localEpochDayOf] (#675). Kept for the places that still bucket in UTC on
 * purpose, and listed in the follow-up issue, so a new caller starts from the local day.
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
