package com.codingpit.muviss.core.designsystem.text

import androidx.compose.runtime.Composable
import com.codingpit.muviss.core.common.civilDateOf
import com.codingpit.muviss.core.designsystem.generated.resources.Res
import com.codingpit.muviss.core.designsystem.generated.resources.date_day_month_year
import com.codingpit.muviss.core.designsystem.generated.resources.date_month_1
import com.codingpit.muviss.core.designsystem.generated.resources.date_month_10
import com.codingpit.muviss.core.designsystem.generated.resources.date_month_11
import com.codingpit.muviss.core.designsystem.generated.resources.date_month_12
import com.codingpit.muviss.core.designsystem.generated.resources.date_month_2
import com.codingpit.muviss.core.designsystem.generated.resources.date_month_3
import com.codingpit.muviss.core.designsystem.generated.resources.date_month_4
import com.codingpit.muviss.core.designsystem.generated.resources.date_month_5
import com.codingpit.muviss.core.designsystem.generated.resources.date_month_6
import com.codingpit.muviss.core.designsystem.generated.resources.date_month_7
import com.codingpit.muviss.core.designsystem.generated.resources.date_month_8
import com.codingpit.muviss.core.designsystem.generated.resources.date_month_9
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private val MONTHS = listOf(
    Res.string.date_month_1, Res.string.date_month_2, Res.string.date_month_3, Res.string.date_month_4,
    Res.string.date_month_5, Res.string.date_month_6, Res.string.date_month_7, Res.string.date_month_8,
    Res.string.date_month_9, Res.string.date_month_10, Res.string.date_month_11, Res.string.date_month_12,
)

/**
 * A calendar date as "12 Mar 2024" in the user's language (EPIC 31, #74):
 * `core:common`'s `formatEpochDay` without the hard-coded English month names.
 * Day-month-year in both languages this app speaks; a locale that wants
 * another order changes `date_day_month_year`.
 */
@Composable
fun dateText(epochDay: Long): String {
    val date = civilDateOf(epochDay)
    return stringResource(Res.string.date_day_month_year, date.day, stringResource(MONTHS[date.month - 1]), date.year)
}

/** [dateText] outside a composition, e.g. for a snackbar message built in a coroutine. */
suspend fun dateTextAsync(epochDay: Long): String {
    val date = civilDateOf(epochDay)
    return getString(Res.string.date_day_month_year, date.day, getString(MONTHS[date.month - 1]), date.year)
}
