package com.codingpit.muviss.core.common

/**
 * Epoch day to a calendar date, without a date-time library.
 *
 * The project has no `kotlinx-datetime` dependency and stores dates as epoch
 * days throughout (see [todayEpochDay] and `Episode.airDateEpochDay`), so
 * anything that wants to *show* one needs this conversion. `feature/progress`
 * carries a private copy for its weekday labels; this is the shared one.
 *
 * Howard Hinnant's `civil_from_days`, the same algorithm `TmdbMapper`'s
 * `epochDayFromDate` inverts. The `z - (DAYS_PER_ERA - 1)` shift before
 * dividing is Hinnant's trick for making plain truncating division round
 * toward negative infinity, so pre-1970 dates come out right too.
 */
fun civilDateOf(epochDay: Long): CivilDate {
    val z = epochDay + DAYS_FROM_ERA_ZERO_TO_UNIX_EPOCH
    val era = (if (z >= 0) z else z - (DAYS_PER_ERA - 1)) / DAYS_PER_ERA
    val dayOfEra = z - era * DAYS_PER_ERA // [0, 146096]
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365 // [0, 399]
    val year = yearOfEra + era * DAYS_PER_ERA_YEARS
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100) // [0, 365]
    val monthIndex = (5 * dayOfYear + 2) / MONTH_TO_DAYS_NUMERATOR // [0, 11], March-based
    val day = dayOfYear - (MONTH_TO_DAYS_NUMERATOR * monthIndex + 2) / 5 + 1 // [1, 31]
    val month = if (monthIndex < 10) monthIndex + 3 else monthIndex - 9 // [1, 12]
    return CivilDate(
        year = (if (month <= 2) year + 1 else year).toInt(),
        month = month.toInt(),
        day = day.toInt(),
    )
}

data class CivilDate(val year: Int, val month: Int, val day: Int)

/** A date as `12 Mar 2024` — short, unambiguous, and not locale-dependent. */
fun formatEpochDay(epochDay: Long): String {
    val date = civilDateOf(epochDay)
    return "${date.day} ${MONTH_NAMES[date.month - 1]} ${date.year}"
}

private val MONTH_NAMES = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private const val DAYS_PER_ERA = 146_097L
private const val DAYS_PER_ERA_YEARS = 400L
private const val MONTH_TO_DAYS_NUMERATOR = 153L
private const val DAYS_FROM_ERA_ZERO_TO_UNIX_EPOCH = 719_468L
