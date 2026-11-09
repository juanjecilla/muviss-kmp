package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The primary button's rule (ADR 0037): what it does is read from the library state, and today is fixed at day 20. */
class DetailPrimaryActionTest {

    private val today = 20L
    private val show = MediaId.tmdbTv("1399")
    private val movie = MediaId.tmdbMovie("603")

    private fun episode(season: Int, number: Int, airDay: Long?) = Episode(EpisodeId(show, season, number), season, number, "E$season-$number", airDateEpochDay = airDay)

    private fun showState(vararg seasons: Season, seen: Set<EpisodeId> = emptySet(), status: WatchStatus? = null, saved: Boolean = true) = DetailUiState(
        loading = false,
        details = MediaDetails(summary = MediaSummary(show, "Show"), seasons = seasons.toList()),
        saved = saved,
        seenEpisodes = seen,
        watchStatus = status,
    )

    @Test
    fun an_unsaved_title_offers_to_add_it() {
        assertEquals(PrimaryAction.Add, primaryActionFor(showState(saved = false), today))
    }

    @Test
    fun an_unwatched_movie_offers_to_mark_it_watched() {
        val state = DetailUiState(loading = false, details = MediaDetails(MediaSummary(movie, "Movie")), saved = true)

        assertEquals(PrimaryAction.MarkMovieWatched, primaryActionFor(state, today))
    }

    @Test
    fun a_watched_movie_opens_the_watched_again_dialog_with_its_count() {
        val state = DetailUiState(
            loading = false,
            details = MediaDetails(MediaSummary(movie, "Movie")),
            saved = true,
            seenEpisodes = setOf(EpisodeId.forMovie(movie)),
            playCounts = mapOf(EpisodeId.forMovie(movie) to 3),
        )

        assertEquals(PrimaryAction.WatchedAgain(3), primaryActionFor(state, today))
    }

    @Test
    fun the_next_aired_unseen_episode_is_the_tick() {
        val first = episode(1, 1, 10L)
        val state = showState(Season(1, "S1", listOf(first, episode(1, 2, 11L))))

        assertEquals(PrimaryAction.TickNext(first), primaryActionFor(state, today))
    }

    @Test
    fun a_season_zero_special_is_never_the_next_episode() {
        val special = episode(0, 1, 10L)
        val regular = episode(1, 1, 12L)
        val state = showState(Season(0, "Specials", listOf(special)), Season(1, "S1", listOf(regular)))

        assertEquals(PrimaryAction.TickNext(regular), primaryActionFor(state, today))
    }

    @Test
    fun an_unaired_episode_before_the_next_aired_one_is_skipped() {
        val unaired = episode(1, 1, 9_999L)
        val aired = episode(1, 2, 12L)
        val state = showState(Season(1, "S1", listOf(unaired, aired)))

        assertEquals(PrimaryAction.TickNext(aired), primaryActionFor(state, today))
    }

    @Test
    fun caught_up_is_disabled_and_names_the_announced_next_episode() {
        val seen = episode(1, 1, 10L)
        val announced = episode(1, 2, 99L)
        val state = showState(Season(1, "S1", listOf(seen, announced)), seen = setOf(seen.id))

        val action = assertIs<PrimaryAction.Waiting>(primaryActionFor(state, today))
        assertEquals(PrimaryAction.Waiting.Reason.CaughtUp, action.reason)
        assertEquals(announced, action.next)
    }

    @Test
    fun a_show_with_nothing_aired_is_not_aired_yet_with_its_premiere() {
        val premiere = episode(1, 1, 99L)
        val state = showState(Season(1, "S1", listOf(premiere)))

        val action = assertIs<PrimaryAction.Waiting>(primaryActionFor(state, today))
        assertEquals(PrimaryAction.Waiting.Reason.NotAiredYet, action.reason)
        assertEquals(premiere, action.next)
    }

    @Test
    fun an_ended_show_with_everything_seen_is_finished() {
        val only = episode(1, 1, 10L)
        val state = showState(Season(1, "S1", listOf(only)), seen = setOf(only.id), status = WatchStatus.FINISHED)

        val action = assertIs<PrimaryAction.Waiting>(primaryActionFor(state, today))
        assertEquals(PrimaryAction.Waiting.Reason.Finished, action.reason)
    }
}
