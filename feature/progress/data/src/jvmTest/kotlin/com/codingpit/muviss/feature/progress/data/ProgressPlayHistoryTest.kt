@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.data

import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.widget.NoOpWidgetRefresher
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class PlayDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

/**
 * Rewatch history (ADR 0011). The invariant under test throughout is that
 * `episodeProgress.seen` and `episodePlay` never disagree: `seen` stays the
 * stored column status derivation and the sync change-log key off, and every
 * write goes through this repository so the two can't drift.
 */
class ProgressPlayHistoryTest {

    private lateinit var repository: SqlDelightProgressRepository
    private lateinit var clock: FakeClock

    private val show = MediaId.tmdbTv("1399")
    private val ep1 = EpisodeId(show, 1, 1)
    private val ep2 = EpisodeId(show, 1, 2)
    private val movie = MediaId.tmdbMovie("603")

    @BeforeTest
    fun setUp() {
        val database = inMemoryDatabase()
        clock = FakeClock(1_000L)
        repository = SqlDelightProgressRepository(
            database.episodeProgressQueries,
            database.episodePlayQueries,
            PlayDispatchers(UnconfinedTestDispatcher()),
            clock,
            NoOpWidgetRefresher,
        )
    }

    private suspend fun seenOf(episodeId: EpisodeId): Boolean = repository.observeForMedia(episodeId.show).let { flow ->
        var result = false
        flow.test {
            result = awaitItem().firstOrNull { it.episodeId == episodeId }?.seen ?: false
            cancelAndIgnoreRemainingEvents()
        }
        result
    }

    private suspend fun playCount(episodeId: EpisodeId): Int {
        var count = 0
        repository.observePlayCounts(episodeId.show).test {
            count = awaitItem()[episodeId] ?: 0
            cancelAndIgnoreRemainingEvents()
        }
        return count
    }

    @Test
    fun recording_a_play_ticks_the_episode() = runTest {
        repository.recordPlay(ep1)

        assertTrue(seenOf(ep1))
        assertEquals(1, playCount(ep1))
    }

    @Test
    fun watching_again_adds_a_play_without_changing_seen() = runTest {
        repository.recordPlay(ep1)
        clock.advanceTo(2_000L)
        repository.recordPlay(ep1)
        clock.advanceTo(3_000L)
        repository.recordPlay(ep1)

        assertEquals(3, playCount(ep1))
        assertTrue(seenOf(ep1))
    }

    @Test
    fun removing_the_latest_play_drops_one_viewing_and_keeps_the_rest() = runTest {
        repository.recordPlay(ep1)
        clock.advanceTo(2_000L)
        repository.recordPlay(ep1)
        clock.advanceTo(3_000L)
        repository.recordPlay(ep1)

        repository.removeLatestPlay(ep1)

        assertEquals(2, playCount(ep1))
        assertTrue(seenOf(ep1), "two viewings remain, so it is still watched")
        repository.observePlays(ep1).test {
            assertEquals(listOf(2_000L, 1_000L), awaitItem().map { it.watchedAtEpochMs })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun removing_the_only_play_unticks_the_episode() = runTest {
        repository.recordPlay(ep1)

        repository.removeLatestPlay(ep1)

        assertEquals(0, playCount(ep1))
        assertFalse(seenOf(ep1), "with no viewings left it was never watched")
    }

    @Test
    fun removing_a_play_from_an_unwatched_episode_does_nothing() = runTest {
        repository.removeLatestPlay(ep1)

        assertEquals(0, playCount(ep1))
        assertFalse(seenOf(ep1))
    }

    @Test
    fun clearing_plays_wipes_the_whole_history_for_one_episode() = runTest {
        repository.recordPlay(ep1)
        clock.advanceTo(2_000L)
        repository.recordPlay(ep1)
        repository.recordPlay(ep2)

        repository.clearPlays(ep1)

        assertEquals(0, playCount(ep1))
        assertFalse(seenOf(ep1))
        assertEquals(1, playCount(ep2), "clearing one episode leaves its neighbours alone")
    }

    @Test
    fun a_bulk_mark_gives_one_play_to_each_previously_unseen_episode() = runTest {
        repository.recordPlay(ep1)
        clock.advanceTo(2_000L)

        repository.recordPlaysForUnseen(listOf(ep1, ep2))

        assertEquals(1, playCount(ep1), "marking a season seen is catching up, not claiming a rewatch")
        assertEquals(1, playCount(ep2))
    }

    @Test
    fun a_bulk_mark_reports_only_the_episodes_it_actually_ticked() = runTest {
        repository.recordPlay(ep1)

        val written = repository.recordPlaysForUnseen(listOf(ep1, ep2))

        assertEquals(listOf(ep2), written, "the undo snackbar has to take back exactly what was written")
    }

    @Test
    fun a_bulk_unmark_mirrors_the_single_episode_undo() = runTest {
        // ep1 genuinely rewatched, ep2 seen once.
        repository.recordPlay(ep1)
        clock.advanceTo(2_000L)
        repository.recordPlay(ep1)
        repository.recordPlay(ep2)

        repository.removeLatestPlays(listOf(ep1, ep2))

        assertEquals(1, playCount(ep1))
        assertTrue(seenOf(ep1), "a title watched twice does not become unwatched by one undo")
        assertEquals(0, playCount(ep2))
        assertFalse(seenOf(ep2))
    }

    @Test
    fun clearing_a_title_removes_its_plays_too() = runTest {
        repository.recordPlay(ep1)
        repository.recordPlay(ep2)

        repository.clearForMedia(show)

        assertEquals(0, playCount(ep1))
        assertEquals(0, playCount(ep2))
        assertFalse(seenOf(ep1))
    }

    @Test
    fun setSeen_true_records_a_first_viewing_and_is_idempotent() = runTest {
        repository.setSeen(ep1, true)
        repository.setSeen(ep1, true)

        assertEquals(1, playCount(ep1), "re-asserting 'seen' is not the same as saying 'watched again'")
        assertTrue(seenOf(ep1))
    }

    @Test
    fun setSeen_false_clears_the_history() = runTest {
        repository.recordPlay(ep1)
        clock.advanceTo(2_000L)
        repository.recordPlay(ep1)

        repository.setSeen(ep1, false)

        assertEquals(0, playCount(ep1))
        assertFalse(seenOf(ep1))
    }

    @Test
    fun movies_record_plays_through_their_synthetic_episode_id() = runTest {
        val id = EpisodeId.forMovie(movie)

        repository.recordPlay(id)
        clock.advanceTo(2_000L)
        repository.recordPlay(id)

        assertEquals(2, playCount(id))
        assertTrue(seenOf(id))
    }

    @Test
    fun the_activity_calendar_reads_play_dates_so_a_rewatch_adds_a_day() = runTest {
        val dayOne = 86_400_000L
        val dayFive = 5 * 86_400_000L

        clock.advanceTo(dayOne)
        repository.recordPlay(ep1)
        clock.advanceTo(dayFive)
        repository.recordPlay(ep1)

        repository.observeSeenActivityEpochDays().test {
            assertEquals(
                setOf(1L, 5L),
                awaitItem(),
                "reading tick timestamps instead would have moved day 1 to day 5 rather than recording both",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
