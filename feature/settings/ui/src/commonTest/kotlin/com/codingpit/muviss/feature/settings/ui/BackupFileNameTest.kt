package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.epochDayOfCivil
import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupFileNameTest {

    private val hour = 3_600_000L

    private fun zone(utcOffsetMs: Long, nowMs: Long) = object : AppClock {
        override fun nowEpochMs(): Long = nowMs
        override fun utcOffsetMsAt(epochMs: Long): Long = utcOffsetMs
    }

    /** 23:30 UTC on 9 Nov is already 10 Nov at UTC+2: the file is dated the day the person sees. */
    @Test
    fun a_late_export_is_dated_by_the_persons_day_not_the_utc_day() {
        val nowMs = epochMsAtStartOfDay(epochDayOfCivil(2026, 11, 9)) + 23 * hour + 30 * 60_000L

        assertEquals("muviss-backup-2026-11-10.json", backupFileName(zone(2 * hour, nowMs)))
    }

    /** 00:10 on 10 Nov at UTC+2 is 22:10 UTC on 9 Nov, just past the person's midnight. */
    @Test
    fun the_first_minutes_of_a_new_local_day_are_the_new_day() {
        val nowMs = epochMsAtStartOfDay(epochDayOfCivil(2026, 11, 9)) + 22 * hour + 10 * 60_000L

        assertEquals("muviss-backup-2026-11-10.json", backupFileName(zone(2 * hour, nowMs)))
    }
}
