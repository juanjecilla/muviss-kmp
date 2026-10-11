package com.codingpit.muviss.feature.profile.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class LastSyncedTest {

    @Test
    fun null_timestamp_means_never_synced() {
        assertEquals(LastSynced.Never, lastSynced(null, nowEpochMs = 1_000L))
    }

    @Test
    fun under_a_minute_reads_just_now() {
        assertEquals(LastSynced.JustNow, lastSynced(lastSyncedAtEpochMs = 1_000L, nowEpochMs = 1_500L))
    }

    @Test
    fun a_clock_that_ran_backwards_reads_just_now() {
        assertEquals(LastSynced.JustNow, lastSynced(lastSyncedAtEpochMs = 9_000L, nowEpochMs = 1_000L))
    }

    @Test
    fun minutes_ago() {
        assertEquals(LastSynced.MinutesAgo(5), lastSynced(lastSyncedAtEpochMs = 0L, nowEpochMs = 5 * 60_000L))
    }

    @Test
    fun the_last_minute_of_the_hour_is_still_minutes() {
        assertEquals(LastSynced.MinutesAgo(59), lastSynced(lastSyncedAtEpochMs = 0L, nowEpochMs = 59 * 60_000L))
    }

    @Test
    fun hours_ago() {
        assertEquals(LastSynced.HoursAgo(3), lastSynced(lastSyncedAtEpochMs = 0L, nowEpochMs = 3 * 3_600_000L))
    }

    @Test
    fun days_ago() {
        assertEquals(LastSynced.DaysAgo(2), lastSynced(lastSyncedAtEpochMs = 0L, nowEpochMs = 2 * 86_400_000L))
    }
}
