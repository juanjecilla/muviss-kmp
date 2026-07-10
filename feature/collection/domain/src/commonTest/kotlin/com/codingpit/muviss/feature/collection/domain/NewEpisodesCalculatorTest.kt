package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TODAY = 100L

/**
 * Covers EPIC 5's acceptance criteria for the pure diff: an aired-count
 * increase produces exactly one notification per show, per-show mute
 * excludes a show regardless of its diff, and a show with no change (or a
 * decrease) produces nothing. The global notifications toggle is
 * deliberately not modeled here — [NewEpisodesCalculator] doesn't know about
 * it; the caller (the Android worker) checks it before running this at all.
 */
class NewEpisodesCalculatorTest {

    private val severance = MediaId.tmdbTv("95396")
    private val theBear = MediaId.tmdbTv("136315")
    private val movie = MediaId.tmdbMovie("603")

    private fun entry(mediaId: MediaId, title: String, airedEpisodes: Int, muted: Boolean = false) = CollectionEntry(
        mediaId = mediaId,
        title = title,
        posterUrl = null,
        releaseYear = null,
        productionStatus = ProductionStatus.RETURNING,
        totalEpisodes = 10,
        airedEpisodes = airedEpisodes,
        favorite = false,
        addedAtEpochMs = 0L,
        notificationsMuted = muted,
    )

    private fun episode(show: MediaId, season: Int, number: Int, airDateEpochDay: Long) = Episode(
        id = EpisodeId(show, season, number),
        seasonNumber = season,
        episodeNumber = number,
        name = "S${season}E$number",
        airDateEpochDay = airDateEpochDay,
    )

    private fun tvDetails(mediaId: MediaId, title: String, vararg episodes: Episode) = MediaDetails(
        summary = MediaSummary(mediaId, title),
        productionStatus = ProductionStatus.RETURNING,
        seasons = listOf(Season(1, "S1", episodes.toList())),
    )

    /**
     * A show's fresh TMDB snapshot: [priorAiredCount] filler episodes that
     * already aired (standing in for whatever was aired before this refresh)
     * plus one new one at [newSeason]/[newNumber] airing today — the aired
     * *count* the calculator diffs against [priorAiredCount] is the sum of
     * all of them, matching how a real TMDB response carries the whole
     * season/episode catalog, not just what's new.
     */
    private fun tvDetailsWithNewEpisode(mediaId: MediaId, title: String, priorAiredCount: Int, newSeason: Int, newNumber: Int) = tvDetails(
        mediaId,
        title,
        *(1..priorAiredCount).map { number -> episode(mediaId, 1, number, TODAY - priorAiredCount + number) }.toTypedArray(),
        episode(mediaId, newSeason, newNumber, TODAY),
    )

    @Test
    fun aired_count_increase_produces_exactly_one_notification_with_the_new_episode_label() {
        val before = listOf(entry(severance, "Severance", airedEpisodes = 4))
        val after = listOf(tvDetailsWithNewEpisode(severance, "Severance", priorAiredCount = 4, newSeason = 2, newNumber = 5))

        val result = NewEpisodesCalculator.diff(before, after, mutedMediaIds = emptySet(), todayEpochDay = TODAY)

        assertEquals(1, result.size)
        assertEquals(NewEpisodeNotification(severance, "Severance", newEpisodeCount = 1, latestEpisodeLabel = "S02E05"), result.single())
    }

    @Test
    fun no_aired_count_change_produces_no_notification() {
        val before = listOf(entry(severance, "Severance", airedEpisodes = 1))
        val after = listOf(tvDetails(severance, "Severance", episode(severance, 1, 1, TODAY)))

        val result = NewEpisodesCalculator.diff(before, after, mutedMediaIds = emptySet(), todayEpochDay = TODAY)

        assertTrue(result.isEmpty())
    }

    @Test
    fun muted_show_is_excluded_even_when_its_aired_count_increased() {
        val before = listOf(entry(severance, "Severance", airedEpisodes = 4, muted = true))
        val after = listOf(tvDetailsWithNewEpisode(severance, "Severance", priorAiredCount = 4, newSeason = 2, newNumber = 5))

        val result = NewEpisodesCalculator.diff(before, after, mutedMediaIds = setOf(severance), todayEpochDay = TODAY)

        assertTrue(result.isEmpty())
    }

    @Test
    fun each_updated_show_gets_its_own_notification() {
        val before = listOf(
            entry(severance, "Severance", airedEpisodes = 4),
            entry(theBear, "The Bear", airedEpisodes = 2),
        )
        val after = listOf(
            tvDetailsWithNewEpisode(severance, "Severance", priorAiredCount = 4, newSeason = 2, newNumber = 5),
            tvDetailsWithNewEpisode(theBear, "The Bear", priorAiredCount = 2, newSeason = 1, newNumber = 3),
        )

        val result = NewEpisodesCalculator.diff(before, after, mutedMediaIds = emptySet(), todayEpochDay = TODAY)

        assertEquals(setOf(severance, theBear), result.map { it.mediaId }.toSet())
        assertEquals(1, result.count { it.mediaId == severance })
        assertEquals(1, result.count { it.mediaId == theBear })
    }

    @Test
    fun a_newly_released_movie_notifies_without_an_episode_label() {
        val before = listOf(entry(movie, "The Matrix", airedEpisodes = 0))
        val after = listOf(MediaDetails(MediaSummary(movie, "The Matrix"), productionStatus = ProductionStatus.RELEASED))

        val result = NewEpisodesCalculator.diff(before, after, mutedMediaIds = emptySet(), todayEpochDay = TODAY)

        assertEquals(NewEpisodeNotification(movie, "The Matrix", newEpisodeCount = 1, latestEpisodeLabel = null), result.single())
    }

    @Test
    fun a_show_missing_from_the_before_list_is_skipped_rather_than_crashing() {
        val after = listOf(tvDetails(severance, "Severance", episode(severance, 1, 1, TODAY)))

        val result = NewEpisodesCalculator.diff(before = emptyList(), after = after, mutedMediaIds = emptySet(), todayEpochDay = TODAY)

        assertTrue(result.isEmpty())
    }
}
