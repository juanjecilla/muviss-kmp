package com.codingpit.muviss.core.model

import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.WatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchStatusCalculatorTest {

    private fun tv(seen: Int, aired: Int, total: Int, prod: ProductionStatus) = WatchProgress(MediaType.TV, seen, aired, total, prod)

    private fun movie(seen: Int) = WatchProgress(MediaType.MOVIE, seen, airedEpisodes = 1, totalEpisodes = 1, ProductionStatus.RELEASED)

    @Test
    fun movie_unseen_is_not_started() {
        assertEquals(WatchStatus.NOT_STARTED, WatchStatusCalculator.derive(movie(0)))
    }

    @Test
    fun movie_seen_is_watched() {
        assertEquals(WatchStatus.WATCHED, WatchStatusCalculator.derive(movie(1)))
    }

    @Test
    fun tv_zero_seen_is_not_started() {
        assertEquals(
            WatchStatus.NOT_STARTED,
            WatchStatusCalculator.derive(tv(0, aired = 10, total = 10, ProductionStatus.ENDED)),
        )
    }

    @Test
    fun tv_partial_is_watching() {
        assertEquals(
            WatchStatus.WATCHING,
            WatchStatusCalculator.derive(tv(3, aired = 10, total = 10, ProductionStatus.ENDED)),
        )
    }

    @Test
    fun tv_all_aired_seen_ongoing_is_watched() {
        // Caught up on a returning show that will air more.
        assertEquals(
            WatchStatus.WATCHED,
            WatchStatusCalculator.derive(tv(6, aired = 6, total = 10, ProductionStatus.RETURNING)),
        )
    }

    @Test
    fun tv_all_seen_ended_is_finished() {
        assertEquals(
            WatchStatus.FINISHED,
            WatchStatusCalculator.derive(tv(10, aired = 10, total = 10, ProductionStatus.ENDED)),
        )
    }

    @Test
    fun tv_new_episode_airs_reverts_watched_from_finished_semantics() {
        // Was caught up (6/6); a new episode airs -> aired becomes 7, still 6 seen -> Watching.
        assertEquals(
            WatchStatus.WATCHING,
            WatchStatusCalculator.derive(tv(6, aired = 7, total = 10, ProductionStatus.RETURNING)),
        )
    }

    @Test
    fun favorite_flag_does_not_affect_status() {
        val base = tv(10, aired = 10, total = 10, ProductionStatus.ENDED)
        assertEquals(
            WatchStatusCalculator.derive(base),
            WatchStatusCalculator.derive(base.copy(favorite = true)),
        )
    }
}
