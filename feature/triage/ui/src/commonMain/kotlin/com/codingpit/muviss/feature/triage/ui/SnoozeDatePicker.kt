package com.codingpit.muviss.feature.triage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.codingpit.muviss.core.common.CivilDate
import com.codingpit.muviss.core.common.civilDateOf
import com.codingpit.muviss.core.common.epochDayOfCivil
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing

/**
 * A free-form date, for anyone the three [com.codingpit.muviss.core.common.flags.SnoozePeriod]
 * presets don't fit (issue #137).
 *
 * Deliberately **not** Material3's `DatePicker`: it is unverified on `js`/
 * `wasmJs` in this Compose Multiplatform version (see the issue), and the
 * project carries no `kotlinx-datetime` dependency to build one against —
 * `core/common/.../CivilDate.kt` exists precisely to avoid that dependency
 * (see its own KDoc). This is a small hand-rolled calendar grid over plain
 * Foundation composables instead, so it renders identically on all six
 * targets by construction rather than by verification.
 *
 * [minEpochDay] is read once, from the clock, by the caller
 * ([TriageViewModel.onPickCustomSnoozeDate]) — this composable never reads a
 * clock of its own, the same discipline [SnoozeChoiceDialog] already follows
 * for the fixed presets.
 */
@Composable
internal fun SnoozeDatePickerDialog(
    title: String,
    minEpochDay: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val minDate = remember(minEpochDay) { civilDateOf(minEpochDay) }
    var visibleYear by remember(minEpochDay) { mutableStateOf(minDate.year) }
    var visibleMonth by remember(minEpochDay) { mutableStateOf(minDate.month) }
    var selectedEpochDay by remember(minEpochDay) { mutableStateOf<Long?>(null) }

    // The earliest month the grid may show — navigating further back would
    // only ever reveal cells before minEpochDay, all of them disabled.
    val canGoBack = visibleYear > minDate.year || (visibleYear == minDate.year && visibleMonth > minDate.month)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { selectedEpochDay?.let(onConfirm) }, enabled = selectedEpochDay != null) {
                Text("Snooze")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Pick a date for $title") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
                modifier = Modifier.testTag(TRIAGE_DATE_PICKER_TAG),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(
                        enabled = canGoBack,
                        onClick = {
                            if (visibleMonth == 1) {
                                visibleMonth = MONTHS_PER_YEAR
                                visibleYear -= 1
                            } else {
                                visibleMonth -= 1
                            }
                        },
                    ) { Icon(MuvissIcons.Back, contentDescription = "Previous month") }
                    Text(
                        text = "${MONTH_LABELS[visibleMonth - 1]} $visibleYear",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    IconButton(
                        onClick = {
                            if (visibleMonth == MONTHS_PER_YEAR) {
                                visibleMonth = 1
                                visibleYear += 1
                            } else {
                                visibleMonth += 1
                            }
                        },
                    ) { Icon(MuvissIcons.ChevronRight, contentDescription = "Next month") }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    WEEKDAY_LABELS.forEach { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                MonthGrid(
                    year = visibleYear,
                    month = visibleMonth,
                    minEpochDay = minEpochDay,
                    selectedEpochDay = selectedEpochDay,
                    onDayClick = { selectedEpochDay = it },
                )
            }
        },
    )
}

/**
 * The day cells themselves, in whole weeks. Plain `Row`/`Column`s rather than
 * `LazyVerticalGrid`: 42 cells at most, so there is nothing to virtualize, and
 * a fixed layout keeps every row's [Modifier.weight] the same width without a
 * grid's own measurement pass.
 */
@Composable
private fun MonthGrid(
    year: Int,
    month: Int,
    minEpochDay: Long,
    selectedEpochDay: Long?,
    onDayClick: (Long) -> Unit,
) {
    val firstOfMonth = epochDayOfCivil(year, month, 1)
    val leadingBlanks = weekdayIndexSundayFirst(firstOfMonth)
    val daysInThisMonth = daysInMonth(year, month)
    val totalCells = leadingBlanks + daysInThisMonth
    val rowCount = (totalCells + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK

    Column {
        for (row in 0 until rowCount) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (column in 0 until DAYS_PER_WEEK) {
                    val cellIndex = row * DAYS_PER_WEEK + column
                    val day = cellIndex - leadingBlanks + 1
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (day in 1..daysInThisMonth) {
                            DayCell(
                                epochDay = firstOfMonth + (day - 1),
                                day = day,
                                enabled = firstOfMonth + (day - 1) >= minEpochDay,
                                selected = firstOfMonth + (day - 1) == selectedEpochDay,
                                onClick = onDayClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(epochDay: Long, day: Int, enabled: Boolean, selected: Boolean, onClick: (Long) -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize(DAY_CELL_FRACTION)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(enabled = enabled) { onClick(epochDay) }
            .testTag("$TRIAGE_DATE_PICKER_DAY_TAG:$epochDay"),
    ) {
        Text(
            text = day.toString(),
            color = when {
                selected -> MaterialTheme.colorScheme.onPrimary
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_DAY_ALPHA)
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** Epoch day → 1-based day count in that civil month, via the same conversion [CivilDate] uses. */
private fun daysInMonth(year: Int, month: Int): Int {
    val nextMonthFirst = if (month == MONTHS_PER_YEAR) epochDayOfCivil(year + 1, 1, 1) else epochDayOfCivil(year, month + 1, 1)
    return (nextMonthFirst - epochDayOfCivil(year, month, 1)).toInt()
}

/**
 * 0 = Sunday .. 6 = Saturday. Epoch day 0 (1970-01-01) was a Thursday, so
 * [WEEKDAY_OFFSET_FOR_EPOCH_ZERO] is what lines the two conventions up; the
 * extra `+ DAYS_PER_WEEK` before the final `%` is only there to keep the
 * result non-negative for a date before the epoch.
 */
private fun weekdayIndexSundayFirst(epochDay: Long): Int = (((epochDay + WEEKDAY_OFFSET_FOR_EPOCH_ZERO) % DAYS_PER_WEEK + DAYS_PER_WEEK) % DAYS_PER_WEEK).toInt()

private const val MONTHS_PER_YEAR = 12
private const val DAYS_PER_WEEK = 7
private const val WEEKDAY_OFFSET_FOR_EPOCH_ZERO = 4L
private const val DAY_CELL_FRACTION = 0.8f
private const val DISABLED_DAY_ALPHA = 0.38f

private val MONTH_LABELS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)
private val WEEKDAY_LABELS = listOf("S", "M", "T", "W", "T", "F", "S")
