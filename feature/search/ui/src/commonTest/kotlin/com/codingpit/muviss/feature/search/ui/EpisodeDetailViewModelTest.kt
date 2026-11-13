@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.epochDayOfCivil
import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import com.codingpit.muviss.feature.search.domain.EpisodeDetailUseCase
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class FakeEpisodeRepo(private val result: Result<EpisodeDetails>) : SearchRepository {
    override suspend fun search(query: String, page: Int) = Result.success(PagedResult(emptyList<MediaSummary>(), page, page))
    override suspend fun trending() = Result.success(emptyList<MediaSummary>())
    override suspend fun details(id: MediaId) = Result.success(MediaDetails(MediaSummary(id, "t")))
    override suspend fun discover(type: MediaType, page: Int, genreId: String?, excludedGenreIds: Set<String>) = Result.success(PagedResult(emptyList<MediaSummary>(), page, page))
    override suspend fun genres(type: MediaType) = Result.success(emptyList<Genre>())
    override suspend fun watchProviders(id: MediaId) = Result.success(WatchProviders())
    override suspend fun recommendations(id: MediaId, page: Int) = Result.success(PagedResult(emptyList<MediaSummary>(), 1, 1))
    override suspend fun similar(id: MediaId, page: Int) = Result.success(PagedResult(emptyList<MediaSummary>(), 1, 1))
    override suspend fun episodeDetails(episodeId: EpisodeId) = result
}

class EpisodeDetailViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val show = MediaId.tmdbTv("1399")
    private val episodeId = EpisodeId(show, 3, 9)
    private val details = EpisodeDetails(episodeId, "The Rains of Castamere", 3, 9, overview = "Robb visits the Twins.")

    private fun viewModel(
        result: Result<EpisodeDetails> = Result.success(details),
        progressApi: FakeProgressApi = FakeProgressApi(),
        clock: AppClock = UtcClock,
    ) = EpisodeDetailViewModel(episodeId, EpisodeDetailUseCase(FakeEpisodeRepo(result)), progressApi, clock)

    private object UtcClock : AppClock {
        override fun nowEpochMs(): Long = 0L
    }

    /** A zone whose offset is [offsetMs] at every instant. */
    private fun zone(offsetMs: Long) = object : AppClock {
        override fun nowEpochMs(): Long = 0L
        override fun utcOffsetMsAt(epochMs: Long): Long = offsetMs
    }

    private val hour = 3_600_000L

    /** The UTC instant at which the civil [day] is at [localHour] in a zone at [offsetMs]. */
    private fun atLocal(day: Triple<Int, Int, Int>, localHour: Long, offsetMs: Long): Long = epochMsAtStartOfDay(epochDayOfCivil(day.first, day.second, day.third)) + localHour * hour - offsetMs

    /** Records one viewing at [watchedAtMs] and returns the play days the screen shows for it. */
    private fun TestScope.playDayAfterWatchingAt(clock: AppClock, watchedAtMs: Long): List<Long> {
        val progressApi = FakeProgressApi().apply { now = watchedAtMs }
        val vm = viewModel(progressApi = progressApi, clock = clock)
        advanceUntilIdle()
        vm.recordRewatch()
        advanceUntilIdle()
        return vm.state.value.playDays
    }

    @Test
    fun it_loads_the_episode() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(details, vm.state.value.details)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun a_failure_surfaces_rather_than_showing_an_empty_page() = runTest {
        val vm = viewModel(result = Result.failure(MetadataError.NotFound()))
        advanceUntilIdle()

        assertEquals(MetadataError.NotFound().userMessage, vm.state.value.error.text())
    }

    @Test
    fun a_raw_exception_never_reaches_the_screen() = runTest {
        val vm = viewModel(result = Result.failure(IllegalStateException("Timeout for https://api.themoviedb.org/3/tv/1?api_key=SECRET")))
        advanceUntilIdle()

        assertEquals("Something went wrong", vm.state.value.error.text())
    }

    @Test
    fun it_shows_the_users_own_watch_history() = runTest {
        val progressApi = FakeProgressApi()
        val vm = viewModel(progressApi = progressApi)
        advanceUntilIdle()

        assertEquals(0, vm.state.value.playCount)
        assertFalse(vm.state.value.seen)

        vm.recordRewatch()
        vm.recordRewatch()
        advanceUntilIdle()

        assertEquals(2, vm.state.value.playCount)
        assertTrue(vm.state.value.seen)
    }

    @Test
    fun undoing_the_latest_viewing_keeps_the_ones_before_it() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.recordRewatch()
        vm.recordRewatch()
        advanceUntilIdle()

        vm.undoLatestPlay()
        advanceUntilIdle()

        assertEquals(1, vm.state.value.playCount)
        assertTrue(vm.state.value.seen)
    }

    @Test
    fun clearing_the_history_forgets_every_viewing() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.recordRewatch()
        vm.recordRewatch()
        vm.recordRewatch()
        advanceUntilIdle()

        vm.clearHistory()
        advanceUntilIdle()

        assertEquals(0, vm.state.value.playCount)
        assertFalse(vm.state.value.seen, "clearing history is what un-watching an episode outright means")
    }

    /**
     * 23:30 on 2 Nov at UTC-5 is 04:30 UTC on 3 Nov. The screen must say 2 Nov, the person's day, not the
     * UTC day the play was stored on (#756).
     */
    @Test
    fun a_late_evening_play_is_dated_by_the_persons_day_not_the_utc_day() = runTest {
        val clock = zone(-5 * hour)
        val watchedAt = atLocal(Triple(2026, 11, 2), localHour = 23, offsetMs = -5 * hour) + 30 * 60_000L

        assertEquals(listOf(epochDayOfCivil(2026, 11, 2)), playDayAfterWatchingAt(clock, watchedAt))
    }

    /** 00:10 on 3 Nov at UTC+2 is 22:10 UTC on 2 Nov: just past local midnight, still the earlier UTC day. */
    @Test
    fun a_play_just_after_local_midnight_is_the_new_day_even_though_utc_is_still_yesterday() = runTest {
        val clock = zone(2 * hour)
        val watchedAt = atLocal(Triple(2026, 11, 3), localHour = 0, offsetMs = 2 * hour) + 10 * 60_000L

        assertEquals(listOf(epochDayOfCivil(2026, 11, 3)), playDayAfterWatchingAt(clock, watchedAt))
    }

    /**
     * Berlin-like, with the change on 29 Mar 2026 at 01:00 UTC. Both plays are on the local day of the change.
     * The one at 00:30 is before the change (+1), the one at 23:30 is after it (+2). Each is read at its own
     * offset, so both land on 29 Mar and neither drifts to 28 or 30 Mar.
     */
    @Test
    fun plays_on_a_dst_change_day_are_dated_at_the_offset_in_force_at_each_play() = runTest {
        val dstChange = epochMsAtStartOfDay(epochDayOfCivil(2026, 3, 29)) + hour
        val berlin = object : AppClock {
            override fun nowEpochMs(): Long = 0L
            override fun utcOffsetMsAt(epochMs: Long): Long = if (epochMs < dstChange) hour else 2 * hour
        }
        val day = Triple(2026, 3, 29)
        val progressApi = FakeProgressApi()
        val vm = viewModel(progressApi = progressApi, clock = berlin)
        advanceUntilIdle()

        progressApi.now = atLocal(day, localHour = 0, offsetMs = hour) + 30 * 60_000L
        vm.recordRewatch()
        progressApi.now = atLocal(day, localHour = 23, offsetMs = 2 * hour)
        vm.recordRewatch()
        advanceUntilIdle()

        val expected = epochDayOfCivil(2026, 3, 29)
        assertEquals(listOf(expected, expected), vm.state.value.playDays)
    }
}
