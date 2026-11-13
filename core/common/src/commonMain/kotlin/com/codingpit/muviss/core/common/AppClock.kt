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
 * Not the person's day: every production day bucket is [localEpochDayOf] (#675, #756). What is left
 * here is the UTC default of [com.codingpit.muviss.feature.profile.domain.MonthlyRewatchCalculator.calculate]
 * for fixtures that have no clock; a new caller that wants "today" or a streak day uses the local day.
 */
fun epochDayOf(epochMs: Long): Long = epochMs / MILLIS_PER_DAY

/**
 * The first millisecond of the person's local day [epochDay] (#756): the earliest instant whose local day is
 * [epochDay] or later, not a midnight guessed from one offset.
 *
 * A DST change can break the midnight guess both ways. A forward jump across midnight skips local 00:00
 * (America/Asuncion, 1 Oct 2023), so the day starts at the jump. A fall-back across midnight shows 00:00 twice
 * (Asia/Gaza, 1 Oct 2004), so the day starts at the first of the two. Both come out of one search: the local
 * day is scanned forward from a point well before the day, to the first half-hour at which it has reached
 * [epochDay], and that single switch is bisected down to the millisecond. Every zone's offset lies within a day
 * of UTC, so the window [midnight - 36 h, midnight + 36 h] always brackets the switch.
 */
fun AppClock.localStartOfDayEpochMs(epochDay: Long): Long {
    val utcMidnight = epochMsAtStartOfDay(epochDay)
    var before = utcMidnight - START_OF_DAY_SPAN_MS
    var after = utcMidnight + START_OF_DAY_SPAN_MS
    var probe = before
    while (probe < after) {
        val next = minOf(probe + START_OF_DAY_SCAN_STEP_MS, after)
        if (localEpochDayOf(next) >= epochDay) {
            before = probe
            after = next
            break
        }
        probe = next
    }
    while (after - before > 1) {
        val middle = before + (after - before) / 2
        if (localEpochDayOf(middle) >= epochDay) after = middle else before = middle
    }
    return after
}

private const val START_OF_DAY_SPAN_MS = 36L * 3_600_000L
private const val START_OF_DAY_SCAN_STEP_MS = 30L * 60_000L

/**
 * The inverse of [epochDayOf]: the first millisecond of [epochDay], UTC.
 *
 * Turns a calendar boundary back into the bound a timestamp query wants —
 * "everything since the start of this year" is an epoch day computed with
 * [epochDayOfCivil] and then widened here.
 */
fun epochMsAtStartOfDay(epochDay: Long): Long = epochDay * MILLIS_PER_DAY
