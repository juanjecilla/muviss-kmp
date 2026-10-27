package com.codingpit.muviss.core.common.review

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewPromptPolicyTest {

    private val day = 86_400_000L
    private val installed = 1_000_000_000_000L
    private val now = installed + ReviewPromptPolicy.MIN_INSTALL_DAYS * day
    private val version = "1.4.0"

    private fun eligible(
        installedAt: Long? = installed,
        ticks: Int = ReviewPromptPolicy.MIN_TICKS,
        lastAskedAt: Long? = null,
        lastAskedVersion: String? = null,
    ) = ReviewRecord(installedAt, ticks, lastAskedAt, lastAskedVersion)

    @Test
    fun may_ask_when_every_rule_is_met_at_its_minimum() {
        assertTrue(ReviewPromptPolicy.mayAsk(eligible(), now, version, recentlyFailed = false))
    }

    @Test
    fun does_not_ask_one_millisecond_before_the_install_period_ends() {
        assertFalse(ReviewPromptPolicy.mayAsk(eligible(), now - 1, version, recentlyFailed = false))
    }

    @Test
    fun does_not_ask_a_fresh_install_on_its_first_day() {
        assertFalse(ReviewPromptPolicy.mayAsk(eligible(), installed, version, recentlyFailed = false))
    }

    @Test
    fun does_not_ask_when_the_install_was_never_stamped() {
        // Null is "not yet eligible", never "installed at the epoch", which would be eligible at once.
        assertFalse(ReviewPromptPolicy.mayAsk(eligible(installedAt = null), Long.MAX_VALUE / 2, version, recentlyFailed = false))
    }

    @Test
    fun does_not_ask_one_tick_short_of_the_minimum() {
        assertFalse(ReviewPromptPolicy.mayAsk(eligible(ticks = ReviewPromptPolicy.MIN_TICKS - 1), now, version, recentlyFailed = false))
    }

    @Test
    fun asks_with_exactly_the_minimum_ticks_and_more_than_it() {
        assertTrue(ReviewPromptPolicy.mayAsk(eligible(ticks = ReviewPromptPolicy.MIN_TICKS + 500), now, version, recentlyFailed = false))
    }

    @Test
    fun does_not_ask_again_before_the_cooldown_ends() {
        val lastAsk = now - ReviewPromptPolicy.COOLDOWN_DAYS * day + 1
        assertFalse(ReviewPromptPolicy.mayAsk(eligible(lastAskedAt = lastAsk, lastAskedVersion = "1.3.0"), now, version, recentlyFailed = false))
    }

    @Test
    fun asks_again_exactly_when_the_cooldown_ends_for_a_new_version() {
        val lastAsk = now - ReviewPromptPolicy.COOLDOWN_DAYS * day
        assertTrue(ReviewPromptPolicy.mayAsk(eligible(lastAskedAt = lastAsk, lastAskedVersion = "1.3.0"), now, version, recentlyFailed = false))
    }

    @Test
    fun does_not_ask_twice_for_the_same_version_even_after_the_cooldown() {
        val lastAsk = now - ReviewPromptPolicy.COOLDOWN_DAYS * day
        assertFalse(ReviewPromptPolicy.mayAsk(eligible(lastAskedAt = lastAsk, lastAskedVersion = version), now, version, recentlyFailed = false))
    }

    @Test
    fun does_not_ask_when_the_clock_reads_earlier_than_the_last_ask() {
        val lastAsk = now + day
        assertFalse(ReviewPromptPolicy.mayAsk(eligible(lastAskedAt = lastAsk, lastAskedVersion = "1.3.0"), now, version, recentlyFailed = false))
    }

    @Test
    fun does_not_ask_while_a_failure_is_standing_even_when_every_other_rule_passes() {
        assertFalse(ReviewPromptPolicy.mayAsk(eligible(), now, version, recentlyFailed = true))
    }
}
