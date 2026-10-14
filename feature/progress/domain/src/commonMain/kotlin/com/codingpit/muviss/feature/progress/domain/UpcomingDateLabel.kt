package com.codingpit.muviss.feature.progress.domain

/**
 * When an episode airs, relative to today, in the shape the Upcoming agenda
 * labels it: "Today", "Tomorrow", a weekday name within the current rolling
 * week, or a month-and-day beyond that.
 *
 * Structured rather than a string since EPIC 31 (#74): the words are the
 * UI's, in the user's language, and the domain only decides which of the four
 * applies. [Weekday.index] is 0 = Sunday … 6 = Saturday; [Date.month] is 1-12.
 */
sealed interface UpcomingDate {
    data object Today : UpcomingDate

    data object Tomorrow : UpcomingDate

    data class Weekday(val index: Int) : UpcomingDate

    data class Date(val month: Int, val day: Int) : UpcomingDate
}

/**
 * Classifies [airDateEpochDay] against [todayEpochDay]. No date library is
 * used — common code must run on every target (see `core/network`'s
 * `TmdbMapper.airDateToEpochDay` KDoc) — so this is plain epoch-day
 * arithmetic: [weekdayIndex] mirrors 1970-01-01 being a Thursday, and
 * [civilDateFrom] is the inverse of the forward days-from-civil algorithm
 * `TmdbMapper` already uses to produce `airDateEpochDay` in the first place
 * (Howard Hinnant's `civil_from_days`).
 */
fun upcomingDate(airDateEpochDay: Long, todayEpochDay: Long): UpcomingDate = when (airDateEpochDay - todayEpochDay) {
    0L -> UpcomingDate.Today
    1L -> UpcomingDate.Tomorrow
    in 2L..6L -> UpcomingDate.Weekday(weekdayIndex(airDateEpochDay))
    else -> civilDateFrom(airDateEpochDay).let { (_, month, day) -> UpcomingDate.Date(month, day) }
}

/** 1970-01-01 (epoch day 0) was a Thursday (index 4), hence the `+ 4` offset. */
private fun weekdayIndex(epochDay: Long): Int = floorMod(epochDay + 4, 7L).toInt()

private fun floorMod(value: Long, modulus: Long): Long = ((value % modulus) + modulus) % modulus

/**
 * Proleptic-Gregorian year/month/day from an epoch day — inverse of
 * `TmdbMapper`'s `epochDayFromDate`. The `z - (DAYS_PER_ERA - 1)` shift
 * before dividing (rather than a general floor-division helper) is
 * Hinnant's own trick for making plain truncating division round toward
 * negative infinity for pre-1970 dates too.
 */
private fun civilDateFrom(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + DAYS_FROM_ERA_ZERO_TO_UNIX_EPOCH
    val era = (if (z >= 0) z else z - (DAYS_PER_ERA - 1)) / DAYS_PER_ERA
    val dayOfEra = z - era * DAYS_PER_ERA // [0, 146096]
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365 // [0, 399]
    val year = yearOfEra + era * DAYS_PER_ERA_YEARS
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100) // [0, 365]
    val monthIndex = (5 * dayOfYear + 2) / MONTH_TO_DAYS_NUMERATOR // [0, 11], March-based
    val day = dayOfYear - (MONTH_TO_DAYS_NUMERATOR * monthIndex + 2) / 5 + 1 // [1, 31]
    val month = if (monthIndex < 10) monthIndex + 3 else monthIndex - 9 // [1, 12]
    val actualYear = if (month <= 2) year + 1 else year
    return Triple(actualYear.toInt(), month.toInt(), day.toInt())
}

private const val DAYS_PER_ERA = 146_097L
private const val DAYS_PER_ERA_YEARS = 400L
private const val MONTH_TO_DAYS_NUMERATOR = 153L
private const val DAYS_FROM_ERA_ZERO_TO_UNIX_EPOCH = 719_468L
