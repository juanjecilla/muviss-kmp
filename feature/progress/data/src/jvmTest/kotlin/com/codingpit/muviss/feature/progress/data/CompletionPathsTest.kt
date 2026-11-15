@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.widget.NoOpWidgetRefresher
import com.codingpit.muviss.core.model.WatchProgress
import com.codingpit.muviss.core.model.WatchStatusCalculator
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.feature.progress.api.Completion
import com.codingpit.muviss.feature.progress.api.CompletionKind
import com.codingpit.muviss.feature.progress.domain.CompletionDetector
import com.codingpit.muviss.feature.progress.domain.MarkAllAiredSeenUseCase
import com.codingpit.muviss.feature.progress.domain.MarkPreviousSeenUseCase
import com.codingpit.muviss.feature.progress.domain.MarkSeasonAiredSeenUseCase
import com.codingpit.muviss.feature.progress.domain.MarkShowAiredSeenUseCase
import com.codingpit.muviss.feature.progress.domain.RecordPlayUseCase
import com.codingpit.muviss.feature.progress.domain.SetMovieWatchedUseCase
import com.codingpit.muviss.feature.progress.domain.TickEpisodeUseCase
import com.codingpit.muviss.feature.progress.domain.TickMovieUseCase
import com.codingpit.muviss.feature.progress.domain.TitleStatusSource
import com.codingpit.muviss.feature.progress.domain.ToggleEpisodeSeenUseCase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Immediate(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

/**
 * #397's "a test per path": over the real repository, with status derived by the real
 * [WatchStatusCalculator], the tick the person makes completes the title exactly once and every other
 * writer is silent. The writers that matter are listed here by the entry point they use; the widgets,
 * import and Triage all call `ToggleEpisodeSeenUseCase`/`MarkAllAiredSeenUseCase`/`SetMovieWatchedUseCase`
 * directly, never `Tick*`, so they are covered by the first three silent cases.
 */
class CompletionPathsTest {

    private val show = MediaId.tmdbTv("1399")
    private val movie = MediaId.tmdbMovie("603")
    private val today = 20_000L
    private val ep1 = EpisodeId(show, 1, 1)
    private val ep2 = EpisodeId(show, 1, 2)

    private fun episode(id: EpisodeId, airedOn: Long?) = Episode(id, id.seasonNumber, id.episodeNumber, "E${id.episodeNumber}", airDateEpochDay = airedOn)
    private val twoAired = listOf(Season(1, "S1", listOf(episode(ep1, today - 10), episode(ep2, today - 3))))
    private val oneAiredOneUpcoming = listOf(Season(1, "S1", listOf(episode(ep1, today - 10), episode(ep2, today + 30))))

    private inner class Rig(production: ProductionStatus, aired: Int) {
        val database = inMemoryDatabase()
        val repository = SqlDelightProgressRepository(
            database.episodeProgressQueries,
            database.episodePlayQueries,
            Immediate(UnconfinedTestDispatcher()),
            FakeClock(1_000L),
            NoOpWidgetRefresher,
        )
        val statuses = TitleStatusSource { id ->
            val seen = repository.observeSeenCount(id).first()
            if (id.type == MediaType.MOVIE) {
                WatchStatusCalculator.derive(WatchProgress(MediaType.MOVIE, seen, 1, 1, ProductionStatus.UNKNOWN))
            } else {
                WatchStatusCalculator.derive(WatchProgress(MediaType.TV, seen, aired, aired, production))
            }
        }
        val detector = CompletionDetector(statuses)
        val toggle = ToggleEpisodeSeenUseCase(repository)
        val tick = TickEpisodeUseCase(toggle, detector)
        val tickMovie = TickMovieUseCase(SetMovieWatchedUseCase(repository), detector)
    }

    /** Runs [block] with a collector on the Completion flow and returns what it saw. */
    private fun kotlinx.coroutines.test.TestScope.collecting(rig: Rig, block: suspend () -> Unit): List<Completion> {
        val seen = mutableListOf<Completion>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.detector.completions.collect { seen += it } }
        runBlockingTest(block)
        job.cancel()
        return seen
    }

    private fun kotlinx.coroutines.test.TestScope.runBlockingTest(block: suspend () -> Unit) {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { block() }
        testScheduler.advanceUntilIdle()
        check(job.isCompleted) { "block did not finish" }
    }

    @Test
    fun the_tick_that_finishes_an_ended_show_emits_finished_once() = runTest {
        val rig = Rig(ProductionStatus.ENDED, aired = 2)
        val seen = collecting(rig) {
            rig.tick(ep1, true)
            rig.tick(ep2, true)
            rig.tick(ep2, true) // already seen: no change
        }
        assertEquals(listOf(Completion(show, CompletionKind.FINISHED)), seen)
    }

    @Test
    fun the_tick_that_catches_up_an_ongoing_show_emits_caught_up() = runTest {
        val rig = Rig(ProductionStatus.RETURNING, aired = 2)
        val seen = collecting(rig) {
            rig.tick(ep1, true)
            rig.tick(ep2, true)
        }
        assertEquals(listOf(Completion(show, CompletionKind.CAUGHT_UP)), seen)
    }

    @Test
    fun a_movie_ticked_watched_emits_finished_and_unticking_emits_nothing() = runTest {
        val rig = Rig(ProductionStatus.UNKNOWN, aired = 1)
        val seen = collecting(rig) {
            rig.tickMovie(movie, true)
            rig.tickMovie(movie, false)
        }
        assertEquals(listOf(Completion(movie, CompletionKind.FINISHED)), seen)
    }

    @Test
    fun re_ticking_after_an_untick_completes_again_because_the_title_was_incomplete_in_between() = runTest {
        val rig = Rig(ProductionStatus.ENDED, aired = 1)
        val seen = collecting(rig) {
            rig.tick(ep1, true)
            rig.tick(ep1, false)
            rig.tick(ep1, true)
        }
        // Two Completions; the once-a-day rule is the prompt policy's job (#398), not detection's.
        assertEquals(2, seen.size)
    }

    // ---- every other writer is silent ------------------------------------------------------------

    @Test
    fun the_widget_import_and_triage_single_tick_path_is_silent() = runTest {
        val rig = Rig(ProductionStatus.ENDED, aired = 2)
        val seen = collecting(rig) {
            rig.toggle(ep1, true)
            rig.toggle(ep2, true) // completes the show, but through the plain use case
        }
        assertTrue(seen.isEmpty(), "ToggleEpisodeSeenUseCase must not emit: $seen")
    }

    @Test
    fun a_movie_set_watched_through_the_plain_use_case_is_silent() = runTest {
        val rig = Rig(ProductionStatus.UNKNOWN, aired = 1)
        val seen = collecting(rig) { SetMovieWatchedUseCase(rig.repository)(movie, true) }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun triage_caught_up_marking_every_aired_episode_is_silent() = runTest {
        val rig = Rig(ProductionStatus.ENDED, aired = 2)
        val seen = collecting(rig) { MarkAllAiredSeenUseCase(rig.repository)(twoAired, today) }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun marking_a_season_or_the_show_seen_is_silent() = runTest {
        val rig = Rig(ProductionStatus.ENDED, aired = 2)
        val seen = collecting(rig) {
            MarkSeasonAiredSeenUseCase(rig.repository)(twoAired.single(), today)
            MarkShowAiredSeenUseCase(rig.repository)(twoAired, today)
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun catching_up_through_an_episode_is_silent() = runTest {
        val rig = Rig(ProductionStatus.ENDED, aired = 2)
        val seen = collecting(rig) { MarkPreviousSeenUseCase(rig.repository)(twoAired, ep2) }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun a_rewatch_play_is_silent_and_does_not_change_status() = runTest {
        val rig = Rig(ProductionStatus.ENDED, aired = 2)
        rig.toggle(ep1, true)
        rig.toggle(ep2, true)
        val seen = collecting(rig) { RecordPlayUseCase(rig.repository)(ep1) }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun a_show_that_ends_after_a_refresh_is_not_a_tick() = runTest {
        // Watched (ongoing, caught up) to Finished (ended) with no tick in between: both statuses are
        // complete, so even a tick that "rewrote" the same state could not report a Completion.
        assertEquals(null, CompletionDetector.completionOf(show, com.codingpit.muviss.models.WatchStatus.WATCHED, com.codingpit.muviss.models.WatchStatus.FINISHED))
    }

    @Test
    fun a_title_whose_upcoming_episode_is_unaired_still_completes_on_the_last_aired_one() = runTest {
        val rig = Rig(ProductionStatus.RETURNING, aired = 1)
        val seen = collecting(rig) { rig.tick(ep1, true) }
        assertEquals(listOf(Completion(show, CompletionKind.CAUGHT_UP)), seen)
        assertEquals(1, oneAiredOneUpcoming.single().episodes.count { (it.airDateEpochDay ?: Long.MAX_VALUE) <= today })
    }
}
