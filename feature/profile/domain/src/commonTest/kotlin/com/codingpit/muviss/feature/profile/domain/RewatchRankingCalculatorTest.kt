package com.codingpit.muviss.feature.profile.domain

import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RewatchRankingCalculatorTest {

    private val office = MediaId.tmdbTv("2316")
    private val fleabag = MediaId.tmdbTv("67070")
    private val poorThings = MediaId.tmdbMovie("792307")
    private val arrival = MediaId.tmdbMovie("329865")

    private fun summary(id: MediaId, title: String) = CollectionSummary(
        mediaId = id,
        title = title,
        posterUrl = "poster/$title",
        status = WatchStatus.WATCHING,
    )

    private val library = listOf(
        summary(office, "The Office"),
        summary(fleabag, "Fleabag"),
        summary(poorThings, "Poor Things"),
        summary(arrival, "Arrival"),
    )

    @Test
    fun splits_shows_and_movies_each_sorted_most_rewatched_first() {
        val ranking = RewatchRankingCalculator.calculate(
            rewatchCounts = mapOf(office to 41, fleabag to 6, poorThings to 5, arrival to 3),
            summaries = library,
        )

        assertEquals(listOf("The Office", "Fleabag"), ranking.shows.map { it.title })
        assertEquals(listOf(41, 6), ranking.shows.map { it.rewatches })
        assertEquals(listOf("Poor Things", "Arrival"), ranking.movies.map { it.title })
    }

    /**
     * A removed title keeps its plays on disk, so it is still in the counts —
     * but the ranking is a view of the library, and it is not in that
     * (ADR 0012).
     */
    @Test
    fun drops_a_title_that_is_no_longer_in_the_library() {
        val ranking = RewatchRankingCalculator.calculate(
            rewatchCounts = mapOf(office to 41, fleabag to 6),
            summaries = library.filterNot { it.mediaId == office },
        )

        assertEquals(listOf("Fleabag"), ranking.shows.map { it.title })
    }

    @Test
    fun a_title_watched_only_once_is_absent_not_a_zero() {
        val ranking = RewatchRankingCalculator.calculate(
            rewatchCounts = mapOf(office to 0),
            summaries = library,
        )

        assertTrue(ranking.isEmpty)
    }

    @Test
    fun titles_on_equal_counts_are_ordered_by_title_so_the_list_never_reshuffles_itself() {
        val ranking = RewatchRankingCalculator.calculate(
            rewatchCounts = mapOf(office to 4, fleabag to 4),
            summaries = library,
        )

        assertEquals(listOf("Fleabag", "The Office"), ranking.shows.map { it.title })
    }

    @Test
    fun carries_the_poster_through_for_the_card_to_render() {
        val ranking = RewatchRankingCalculator.calculate(mapOf(fleabag to 2), library)

        assertEquals("poster/Fleabag", ranking.shows.single().posterUrl)
    }

    /** The card mixes types; every row prints its own unit, which is what makes that legible. */
    @Test
    fun top_takes_the_highest_scorers_across_both_lists() {
        val ranking = RewatchRankingCalculator.calculate(
            rewatchCounts = mapOf(office to 41, fleabag to 6, poorThings to 18, arrival to 1),
            summaries = library,
        )

        assertEquals(listOf("The Office", "Poor Things", "Fleabag"), ranking.top(3).map { it.title })
    }

    @Test
    fun an_empty_library_ranks_nothing() {
        assertTrue(RewatchRankingCalculator.calculate(mapOf(office to 41), emptyList()).isEmpty)
    }
}
