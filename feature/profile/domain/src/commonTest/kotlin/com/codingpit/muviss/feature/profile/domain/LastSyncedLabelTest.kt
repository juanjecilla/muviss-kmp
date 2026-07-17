package com.codingpit.muviss.feature.profile.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class LastSyncedLabelTest {

    @Test
    fun null_timestamp_means_never_synced() {
        assertEquals("Never synced", lastSyncedLabel(null, nowEpochMs = 1_000L))
    }

    @Test
    fun under_a_minute_reads_just_now() {
        assertEquals("Synced just now", lastSyncedLabel(lastSyncedAtEpochMs = 1_000L, nowEpochMs = 1_500L))
    }

    @Test
    fun minutes_ago() {
        assertEquals("Synced 5m ago", lastSyncedLabel(lastSyncedAtEpochMs = 0L, nowEpochMs = 5 * 60_000L))
    }

    @Test
    fun hours_ago() {
        assertEquals("Synced 3h ago", lastSyncedLabel(lastSyncedAtEpochMs = 0L, nowEpochMs = 3 * 3_600_000L))
    }

    @Test
    fun days_ago() {
        assertEquals("Synced 2d ago", lastSyncedLabel(lastSyncedAtEpochMs = 0L, nowEpochMs = 2 * 86_400_000L))
    }
}
