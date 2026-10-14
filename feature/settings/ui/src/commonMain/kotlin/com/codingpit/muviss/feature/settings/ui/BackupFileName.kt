package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.core.common.civilDateOf

/**
 * `muviss-backup-2026-10-07.json`: dated, so a second export does not land on
 * top of the first in a Downloads folder, and ISO-ordered so they sort. Not
 * localized — it is a file name, not copy. The date is UTC, like every
 * epoch day in the app.
 */
internal fun backupFileName(nowEpochMs: Long): String {
    val date = civilDateOf(nowEpochMs.floorDiv(MILLIS_PER_DAY))
    return "muviss-backup-${date.year}-${date.month.twoDigits()}-${date.day.twoDigits()}.json"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')

private const val MILLIS_PER_DAY = 86_400_000L
