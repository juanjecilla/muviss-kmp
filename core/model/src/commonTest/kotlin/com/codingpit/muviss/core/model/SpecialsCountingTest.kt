package com.codingpit.muviss.core.model

import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.WatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SpecialsCountingTest {

    @Test
    fun specials_are_left_out_of_both_counts_while_the_setting_is_off() {
        val counted = SpecialsCounting.count(regularSeen = 2, regularAired = 3, specialsSeen = 1, specialsAired = 2, countSpecials = false)

        assertEquals(CountedProgress(seen = 2, aired = 3), counted)
    }

    @Test
    fun specials_are_added_to_both_counts_while_the_setting_is_on() {
        val counted = SpecialsCounting.count(regularSeen = 2, regularAired = 3, specialsSeen = 1, specialsAired = 2, countSpecials = true)

        assertEquals(CountedProgress(seen = 3, aired = 5), counted)
    }

    @Test
    fun an_unseen_aired_special_keeps_a_finished_show_in_progress_only_when_counted() {
        val off = status(countSpecials = false, specialsSeen = 0, specialsAired = 1)
        val on = status(countSpecials = true, specialsSeen = 0, specialsAired = 1)

        assertEquals(WatchStatus.WATCHED, off)
        assertEquals(WatchStatus.WATCHING, on)
    }

    @Test
    fun a_counted_special_never_pushes_seen_above_aired_when_the_catalog_has_none() {
        // specialsSeen is a join on the aired catalog, so with no cached special it is zero whatever was ticked.
        val counted = SpecialsCounting.count(regularSeen = 4, regularAired = 4, specialsSeen = 0, specialsAired = 0, countSpecials = true)

        assertEquals(CountedProgress(seen = 4, aired = 4), counted)
    }

    private fun status(countSpecials: Boolean, specialsSeen: Int, specialsAired: Int): WatchStatus {
        val counted = SpecialsCounting.count(regularSeen = 3, regularAired = 3, specialsSeen = specialsSeen, specialsAired = specialsAired, countSpecials = countSpecials)
        return WatchStatusCalculator.derive(
            WatchProgress(
                mediaType = MediaType.TV,
                seenEpisodes = counted.seen,
                airedEpisodes = counted.aired,
                totalEpisodes = counted.aired,
                // Returning, not ended: an ended show would read Finished, not Watched, and that is not what this asserts.
                productionStatus = ProductionStatus.RETURNING,
            ),
        )
    }
}
