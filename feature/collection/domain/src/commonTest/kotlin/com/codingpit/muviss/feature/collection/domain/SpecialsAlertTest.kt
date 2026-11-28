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

/**
 * Season 0 never changes a snapshot's counts and so never raises an alert (ADR 0037, #938). The new-episode check
 * compares the provider's aired count with the stored one, so a special left out of the count cannot alert.
 */
class SpecialsAlertTest {

    private val show = MediaId.tmdbTv("1399")
    private val today = 100L

    private fun episode(season: Int, number: Int, airDay: Long?) = Episode(EpisodeId(show, season, number), season, number, "E$season-$number", airDateEpochDay = airDay)

    private fun details(vararg seasons: Season) = MediaDetails(
        summary = MediaSummary(show, "Show"),
        productionStatus = ProductionStatus.RETURNING,
        seasons = seasons.toList(),
    )

    private fun entry(airedEpisodes: Int) = CollectionEntry(
        mediaId = show,
        title = "Show",
        posterUrl = null,
        releaseYear = null,
        productionStatus = ProductionStatus.RETURNING,
        totalEpisodes = airedEpisodes,
        airedEpisodes = airedEpisodes,
        favorite = false,
        addedAtEpochMs = 0L,
    )

    @Test
    fun an_aired_special_is_not_in_the_aired_or_total_count() {
        val show = details(
            Season(0, "Specials", listOf(episode(0, 1, airDay = 50))),
            Season(1, "Season 1", listOf(episode(1, 1, airDay = 50), episode(1, 2, airDay = 60))),
        )

        // Both regular episodes have aired and the aired special has too, but the special is not in either count.
        assertEquals(2, show.airedEpisodeCount(todayEpochDay = today))
        assertEquals(2, show.totalEpisodeCount())
    }

    @Test
    fun a_new_aired_special_raises_no_alert() {
        val before = listOf(entry(airedEpisodes = 2))
        val after = listOf(
            details(
                Season(0, "Specials", listOf(episode(0, 1, airDay = 50), episode(0, 2, airDay = 90))),
                Season(1, "Season 1", listOf(episode(1, 1, airDay = 50), episode(1, 2, airDay = 60))),
            ),
        )

        val alerts = NewEpisodesCalculator.diff(before, after, mutedMediaIds = emptySet(), todayEpochDay = 100L)

        assertTrue(alerts.isEmpty(), "a special is not a new episode: $alerts")
    }

    @Test
    fun a_new_regular_episode_still_alerts_and_names_a_regular_episode_not_a_special() {
        val before = listOf(entry(airedEpisodes = 2))
        val after = listOf(
            details(
                Season(0, "Specials", listOf(episode(0, 1, airDay = 90))),
                Season(1, "Season 1", listOf(episode(1, 1, airDay = 50), episode(1, 2, airDay = 60), episode(1, 3, airDay = 70))),
            ),
        )

        val alert = NewEpisodesCalculator.diff(before, after, mutedMediaIds = emptySet(), todayEpochDay = 100L).single()

        assertEquals(1, alert.newEpisodeCount)
        assertEquals("S01E03", alert.latestEpisodeLabel)
    }

    @Test
    fun a_special_airing_last_is_never_named_as_the_latest_episode() {
        val show = details(
            Season(0, "Specials", listOf(episode(0, 1, airDay = 95))),
            Season(1, "Season 1", listOf(episode(1, 1, airDay = 50))),
        )

        assertEquals("S01E01", show.latestAiredEpisodeLabel(todayEpochDay = 100L))
    }
}
