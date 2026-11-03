package com.codingpit.muviss.feature.nextpick.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionPromptPolicyTest {

    private val day = CompletionPromptPolicy.DAY_MS
    private val noon = 12 * 3_600_000L + 10 * day

    @Test
    fun shows_the_first_time_after_onboarding_when_enabled() {
        // Given no prompt has ever been shown, onboarding is done and the switch is on
        // When a completion happens
        val shows = CompletionPromptPolicy.shouldShow(noon, lastShownEpochMs = null, enabled = true, onboardingCompleted = true)
        // Then the sheet may appear
        assertTrue(shows)
    }

    @Test
    fun suppresses_a_second_prompt_the_same_day() {
        // Given the sheet was shown an hour ago
        val shows = CompletionPromptPolicy.shouldShow(noon, lastShownEpochMs = noon - 3_600_000L, enabled = true, onboardingCompleted = true)
        // Then it does not appear again today
        assertFalse(shows)
    }

    @Test
    fun shows_again_on_the_next_day() {
        // Given the sheet was shown yesterday
        val shows = CompletionPromptPolicy.shouldShow(noon, lastShownEpochMs = noon - day, enabled = true, onboardingCompleted = true)
        // Then it may appear today
        assertTrue(shows)
    }

    @Test
    fun never_shows_before_onboarding_completes() {
        // Given a fresh install that has not finished the introduction
        val shows = CompletionPromptPolicy.shouldShow(noon, lastShownEpochMs = null, enabled = true, onboardingCompleted = false)
        // Then no sheet, even on a completion
        assertFalse(shows)
    }

    @Test
    fun never_shows_when_the_settings_switch_is_off() {
        // Given the person turned the completion prompt off (#403)
        val shows = CompletionPromptPolicy.shouldShow(noon, lastShownEpochMs = null, enabled = false, onboardingCompleted = true)
        // Then no sheet
        assertFalse(shows)
    }
}
