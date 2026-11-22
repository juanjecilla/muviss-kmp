package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.flags.SnoozePeriod
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Pick Session's Snooze follows the person's `triageSnoozePeriod` (#551), the same setting the deck reads,
 * instead of a fixed week.
 */
class SnoozeDueEpochDayTest {

    private val today = 20_000L

    @Test
    fun `a one week period comes back seven days on`() {
        assertEquals(today + 7, snoozeDueEpochDay(SnoozePeriod.ONE_WEEK, today))
    }

    @Test
    fun `a one month period comes back thirty days on`() {
        assertEquals(today + 30, snoozeDueEpochDay(SnoozePeriod.ONE_MONTH, today))
    }

    @Test
    fun `a three month period comes back ninety days on`() {
        assertEquals(today + 90, snoozeDueEpochDay(SnoozePeriod.THREE_MONTHS, today))
    }

    @Test
    fun `ask each time has no duration, so a pick session falls back to the default week`() {
        assertEquals(today + 7, snoozeDueEpochDay(SnoozePeriod.ASK_EACH_TIME, today))
    }
}
