package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.WatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun summary(
    id: MediaId,
    favorite: Boolean = false,
    rating: Int? = null,
    addedAtEpochMs: Long = 0,
) = CollectionSummary(
    mediaId = id,
    title = id.toString(),
    posterUrl = null,
    status = WatchStatus.NOT_STARTED,
    favorite = favorite,
    rating = rating,
    addedAtEpochMs = addedAtEpochMs,
)

class ForYouSeedingTest {

    private val movie1 = MediaId.tmdbMovie("1")
    private val movie2 = MediaId.tmdbMovie("2")
    private val movie3 = MediaId.tmdbMovie("3")
    private val movie4 = MediaId.tmdbMovie("4")

    @Test
    fun selectSeeds_is_empty_for_an_empty_library() {
        assertTrue(ForYouSeeding.selectSeeds(emptyList()).isEmpty())
    }

    @Test
    fun selectSeeds_is_empty_when_no_title_is_favorite_or_top_rated() {
        val library = listOf(summary(movie1, rating = 5), summary(movie2, rating = null))
        assertTrue(ForYouSeeding.selectSeeds(library).isEmpty())
    }

    @Test
    fun selectSeeds_includes_favorites() {
        val library = listOf(summary(movie1, favorite = true))
        assertEquals(listOf(movie1), ForYouSeeding.selectSeeds(library))
    }

    @Test
    fun selectSeeds_includes_titles_rated_at_or_above_the_threshold() {
        val library = listOf(summary(movie1, rating = ForYouSeeding.TOP_RATED_THRESHOLD), summary(movie2, rating = ForYouSeeding.TOP_RATED_THRESHOLD - 1))
        assertEquals(listOf(movie1), ForYouSeeding.selectSeeds(library))
    }

    @Test
    fun selectSeeds_orders_by_most_recently_added_first() {
        val library = listOf(
            summary(movie1, favorite = true, addedAtEpochMs = 100),
            summary(movie2, favorite = true, addedAtEpochMs = 300),
            summary(movie3, favorite = true, addedAtEpochMs = 200),
        )
        assertEquals(listOf(movie2, movie3, movie1), ForYouSeeding.selectSeeds(library))
    }

    @Test
    fun selectSeeds_caps_at_maxSeeds() {
        val library = listOf(
            summary(movie1, favorite = true, addedAtEpochMs = 400),
            summary(movie2, favorite = true, addedAtEpochMs = 300),
            summary(movie3, favorite = true, addedAtEpochMs = 200),
            summary(movie4, favorite = true, addedAtEpochMs = 100),
        )
        val seeds = ForYouSeeding.selectSeeds(library, maxSeeds = 3)
        assertEquals(3, seeds.size)
        assertEquals(listOf(movie1, movie2, movie3), seeds)
    }

    @Test
    fun selectSeeds_deduplicates_a_title_that_is_both_favorite_and_top_rated() {
        val library = listOf(summary(movie1, favorite = true, rating = 9))
        assertEquals(listOf(movie1), ForYouSeeding.selectSeeds(library))
    }

    @Test
    fun mergeAndExclude_is_empty_for_no_recommendation_sets() {
        assertTrue(ForYouSeeding.mergeAndExclude(emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun mergeAndExclude_flattens_every_seeds_recommendations() {
        val a = MediaSummary(movie1, "A")
        val b = MediaSummary(movie2, "B")
        val result = ForYouSeeding.mergeAndExclude(listOf(listOf(a), listOf(b)), emptySet())
        assertEquals(listOf(a, b), result)
    }

    @Test
    fun mergeAndExclude_deduplicates_by_id_keeping_the_first_occurrence() {
        val first = MediaSummary(movie1, "First seed's copy")
        val duplicate = MediaSummary(movie1, "Second seed's copy")
        val result = ForYouSeeding.mergeAndExclude(listOf(listOf(first), listOf(duplicate)), emptySet())
        assertEquals(listOf(first), result)
    }

    @Test
    fun mergeAndExclude_drops_titles_already_in_the_library() {
        val alreadySaved = MediaSummary(movie1, "Already saved")
        val newTitle = MediaSummary(movie2, "New")
        val result = ForYouSeeding.mergeAndExclude(listOf(listOf(alreadySaved, newTitle)), setOf(movie1))
        assertEquals(listOf(newTitle), result)
    }
}
