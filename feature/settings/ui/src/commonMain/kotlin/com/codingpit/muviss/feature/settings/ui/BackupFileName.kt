package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.civilDateOf
import com.codingpit.muviss.core.common.localTodayEpochDay

/**
 * `muviss-backup-2026-10-07.json`: dated, so a second export does not land on
 * top of the first in a Downloads folder, and ISO-ordered so they sort. Not
 * localized — it is a file name, not copy. The date is the person's local day,
 * the same day as every other "today" in the app (#756).
 */
internal fun backupFileName(clock: AppClock): String {
    val date = civilDateOf(clock.localTodayEpochDay())
    return "muviss-backup-${date.year}-${date.month.twoDigits()}-${date.day.twoDigits()}.json"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')
