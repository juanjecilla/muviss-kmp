package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.epochDayOfCivil
import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import com.codingpit.muviss.core.common.localEpochDayOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionPromptPolicyTest {

    private val day = DAY_MS
    private val noon = 12 * HOUR + 10 * day

    /** Plays the person's zone: a fixed offset, so the test never reads the host's own zone. */
    private fun zone(utcOffsetMs: Long) = object : AppClock {
        override fun nowEpochMs(): Long = 0L
        override fun utcOffsetMsAt(epochMs: Long): Long = utcOffsetMs
    }

    private fun shows(
        now: Long,
        last: Long?,
        enabled: Boolean = true,
        onboardingCompleted: Boolean = true,
        utcOffsetMs: Long = 0L,
    ): Boolean = CompletionPromptPolicy.shouldShow(
        nowEpochMs = now,
        lastShownEpochMs = last,
        enabled = enabled,
        onboardingCompleted = onboardingCompleted,
        localDayOf = zone(utcOffsetMs)::localEpochDayOf,
    )

    @Test
    fun shows_the_first_time_after_onboarding_when_enabled() {
        assertTrue(shows(noon, last = null))
    }

    @Test
    fun suppresses_a_second_prompt_the_same_day() {
        // Given the sheet was shown an hour ago
        assertFalse(shows(noon, last = noon - HOUR))
    }

    @Test
    fun shows_again_on_the_next_day() {
        assertTrue(shows(noon, last = noon - day))
    }

    @Test
    fun never_shows_before_onboarding_completes() {
        assertFalse(shows(noon, last = null, onboardingCompleted = false))
    }

    @Test
    fun never_shows_when_the_settings_switch_is_off() {
        assertFalse(shows(noon, last = null, enabled = false))
    }

    @Test
    fun a_second_prompt_is_suppressed_when_both_instants_share_a_local_day_across_utc_midnight() {
        // Given UTC+2, the sheet was shown at 00:30 local on 2 Nov, which is 22:30 UTC on 1 Nov
        val first = epochMsAtStartOfDay(epochDayOfCivil(2026, 11, 1)) + 22 * HOUR + 30 * MINUTE
        // When a second completion happens at 23:30 local on 2 Nov, which is 21:30 UTC on 2 Nov
        val second = epochMsAtStartOfDay(epochDayOfCivil(2026, 11, 2)) + 21 * HOUR + 30 * MINUTE
        // Then the UTC days differ, but the person's day is the same: no second sheet
        assertFalse(shows(second, last = first, utcOffsetMs = 2 * HOUR))
    }

    @Test
    fun a_prompt_shows_when_local_midnight_falls_inside_one_utc_day() {
        // Given UTC+2, the sheet was shown at 23:30 local on 1 Nov, which is 21:30 UTC on 1 Nov
        val first = epochMsAtStartOfDay(epochDayOfCivil(2026, 11, 1)) + 21 * HOUR + 30 * MINUTE
        // When a completion happens at 00:30 local on 2 Nov, which is 22:30 UTC on 1 Nov
        val second = epochMsAtStartOfDay(epochDayOfCivil(2026, 11, 1)) + 22 * HOUR + 30 * MINUTE
        // Then the local day has changed, so the sheet may appear even though the UTC day has not
        assertTrue(shows(second, last = first, utcOffsetMs = 2 * HOUR))
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val MINUTE = 60_000L
        const val DAY_MS = 86_400_000L
    }
}
