@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import com.codingpit.muviss.core.designsystem.component.CELEBRATION_BURST_TAG
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.FakeFeatureFlags
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.progress.domain.CompletionDetector
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveLastWatchedAtUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenEpisodesUseCase
import com.codingpit.muviss.feature.progress.domain.TickEpisodeUseCase
import com.codingpit.muviss.feature.progress.domain.ToggleEpisodeSeenUseCase
import com.codingpit.muviss.feature.progress.domain.WatchNextUseCase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a tick looks like on Watch Next (EPIC 52): the row confirms it, a
 * milestone adds the burst, and with motion off only the text remains. The
 * clock is driven by hand because the whole point is what is on screen *during*
 * the animation, which `waitForIdle` would run to its end.
 */
class ProgressTickFeedbackTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_plain_tick_sweeps_the_row_then_clears() = runComposeUiTest {
        setContent { WatchNext(seasons = listOf(season(1, 3)), alreadySeen = 0) }

        tapTick()

        onNodeWithTag(TICK_CONFIRMATION_TAG).assertExists()
        onNodeWithText("Marked S1E1 seen").assertExists()
        mainClock.advanceTimeBy(2_000)
        onNodeWithTag(TICK_CONFIRMATION_TAG).assertDoesNotExist()
        onNodeWithTag(CELEBRATION_BURST_TAG).assertDoesNotExist()
    }

    @Test
    fun a_plain_tick_with_motion_off_shows_no_sweep_but_still_says_so() = runComposeUiTest {
        setContent { WatchNext(seasons = listOf(season(1, 3)), alreadySeen = 0, tickAnimations = false) }

        tapTick()

        onNodeWithTag(TICK_CONFIRMATION_TAG).assertDoesNotExist()
        onNodeWithText("Marked S1E1 seen").assertExists()
    }

    @Test
    fun finishing_a_season_bursts_and_says_which() = runComposeUiTest {
        setContent { WatchNext(seasons = listOf(season(1, 2), season(2, 2)), alreadySeen = 1) }

        tapTick()

        onNodeWithTag(CELEBRATION_BURST_TAG).assertExists()
        onNodeWithText("Season 1 finished").assertExists()
        mainClock.advanceTimeBy(2_000)
        onNodeWithTag(CELEBRATION_BURST_TAG).assertDoesNotExist()
    }

    @Test
    fun finishing_a_season_with_motion_off_has_the_text_and_no_burst() = runComposeUiTest {
        setContent { WatchNext(seasons = listOf(season(1, 2), season(2, 2)), alreadySeen = 1, tickAnimations = false) }

        tapTick()

        onNodeWithTag(CELEBRATION_BURST_TAG).assertDoesNotExist()
        onNodeWithText("Season 1 finished").assertExists()
    }

    @Test
    fun catching_up_bursts_and_names_the_show() = runComposeUiTest {
        setContent { WatchNext(seasons = listOf(season(1, 2)), alreadySeen = 1) }

        tapTick()

        onNodeWithTag(CELEBRATION_BURST_TAG).assertExists()
        onNodeWithText("Caught up on Mini").assertExists()
    }

    @Test
    fun catching_up_with_the_master_switch_off_has_the_text_and_no_burst() = runComposeUiTest {
        setContent { WatchNext(seasons = listOf(season(1, 2)), alreadySeen = 1, animations = false) }

        tapTick()

        onNodeWithTag(CELEBRATION_BURST_TAG).assertDoesNotExist()
        onNodeWithText("Caught up on Mini").assertExists()
    }

    @Test
    fun undo_in_the_snackbar_takes_the_tick_back() = runComposeUiTest {
        val repository = FakeProgressRepository()
        setContent { WatchNext(seasons = listOf(season(1, 3)), alreadySeen = 0, repository = repository) }

        tapTick()
        onNodeWithText("Undo").performClick()
        mainClock.advanceTimeBy(100)

        assertEquals(listOf(EpisodeId(mini, 1, 1) to true, EpisodeId(mini, 1, 1) to false), repository.tickedEpisodes)
    }

    @Test
    fun undo_on_the_newest_tick_after_a_replaced_snackbar_reverts_only_that_tick() = runComposeUiTest {
        val repository = FakeProgressRepository()
        setContent { WatchNext(seasons = listOf(season(1, 3)), alreadySeen = 0, repository = repository) }

        tapTick()
        tapTick()
        mainClock.advanceTimeBy(100)
        onAllNodesWithText("Undo").assertCountEquals(1)
        onNodeWithText("Undo").performClick()
        mainClock.advanceTimeBy(100)

        assertEquals(
            listOf(EpisodeId(mini, 1, 1) to true, EpisodeId(mini, 1, 2) to true, EpisodeId(mini, 1, 2) to false),
            repository.tickedEpisodes,
        )
    }

    @Test
    fun an_undone_tick_can_be_ticked_again() = runComposeUiTest {
        val repository = FakeProgressRepository()
        setContent { WatchNext(seasons = listOf(season(1, 3)), alreadySeen = 0, repository = repository) }
        tapTick()
        onNodeWithText("Undo").performClick()
        mainClock.advanceTimeBy(100)

        tapTick()

        assertEquals(EpisodeId(mini, 1, 1) to true, repository.tickedEpisodes.last())
        assertEquals(3, repository.tickedEpisodes.size)
    }

    @Test
    fun the_undo_snackbar_goes_away_by_itself() = runComposeUiTest {
        setContent { WatchNext(seasons = listOf(season(1, 3)), alreadySeen = 0) }

        tapTick()
        onNodeWithText("Marked S1E1 seen").assertExists()
        mainClock.advanceTimeBy(6_000)

        onNodeWithText("Marked S1E1 seen").assertDoesNotExist()
        onNodeWithText("Undo").assertDoesNotExist()
    }

    @Test
    fun a_second_tick_replaces_the_snackbar_instead_of_queuing_behind_it() = runComposeUiTest {
        setContent { WatchNext(seasons = listOf(season(1, 3)), alreadySeen = 0) }

        tapTick()
        onNodeWithText("Marked S1E1 seen").assertExists()
        tapTick()
        mainClock.advanceTimeBy(1_000) // the old one's exit animation, well inside the 4s timeout

        onNodeWithText("Marked S1E1 seen").assertDoesNotExist()
        onNodeWithText("Marked S1E2 seen").assertExists()
    }

    @Test
    fun the_snackbar_does_not_cover_the_last_row_when_the_list_is_scrolled_to_its_end() = runComposeUiTest {
        setContent { WatchNext(seasons = listOf(season(1, 3)), alreadySeen = 0, extraShows = 12) }
        mainClock.autoAdvance = false

        onAllNodesWithContentDescription("Mark", substring = true)[0].performClick()
        mainClock.advanceTimeBy(100)
        onNodeWithTag(WATCH_NEXT_LIST_TAG).performScrollToIndex(12)
        mainClock.advanceTimeBy(500)

        val snackbarTop = onNodeWithTag(WATCH_NEXT_SNACKBAR_TAG).fetchSemanticsNode().boundsInRoot.top
        val lastRowBottom = onAllNodesWithTag(WATCH_NEXT_ROW_TAG).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        assertTrue(lastRowBottom <= snackbarTop, "last row ends at $lastRowBottom, snackbar starts at $snackbarTop")
    }

    @Test
    fun a_snackbar_wrapped_by_a_long_title_and_a_large_font_still_clears_the_last_row() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                WatchNext(seasons = listOf(season(1, 1)), alreadySeen = 0, extraShows = 12, title = LONG_TITLE)
            }
        }
        mainClock.autoAdvance = false

        onAllNodesWithContentDescription("Mark", substring = true)[0].performClick()
        mainClock.advanceTimeBy(100)
        onNodeWithText("Caught up on $LONG_TITLE").assertExists()
        onNodeWithTag(WATCH_NEXT_LIST_TAG).performScrollToIndex(11)
        mainClock.advanceTimeBy(500)

        val snackbarTop = onNodeWithTag(WATCH_NEXT_SNACKBAR_TAG).fetchSemanticsNode().boundsInRoot.top
        val lastRowBottom = onAllNodesWithTag(WATCH_NEXT_ROW_TAG).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        assertTrue(lastRowBottom <= snackbarTop, "last row ends at $lastRowBottom, snackbar starts at $snackbarTop")
    }

    private fun ComposeUiTest.tapTick() {
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Mark", substring = true).performClick()
        mainClock.advanceTimeBy(100)
    }
}

private val mini = MediaId.tmdbTv("4242")

/** [count] aired episodes in season [number]. */
private const val LONG_TITLE = "The Remarkably Long and Winding Chronicles of a Show With an Unreasonably Long Name"

private fun season(number: Int, count: Int) = Season(
    number = number,
    name = "Season $number",
    episodes = (1..count).map { Episode(EpisodeId(mini, number, it), number, it, "Episode $it", airDateEpochDay = 100L + number * 10 + it) },
)

private fun extraSeason(id: MediaId) = Season(
    number = 1,
    name = "Season 1",
    episodes = (1..2).map { Episode(EpisodeId(id, 1, it), 1, it, "Episode $it", airDateEpochDay = 111L + it) },
)

@Composable
private fun WatchNext(
    seasons: List<Season>,
    alreadySeen: Int,
    tickAnimations: Boolean = true,
    animations: Boolean = true,
    repository: FakeProgressRepository = FakeProgressRepository(),
    extraShows: Int = 0,
    title: String = "Mini",
) {
    val extras = (1..extraShows).associate { MediaId.tmdbTv("9$it") to "Extra $it" }
    val collectionApi = FakeCollectionApi(
        listOf(CollectionSummary(mini, title, posterUrl = null, status = WatchStatus.WATCHING)) +
            extras.map { (id, title) -> CollectionSummary(id, title, posterUrl = null, status = WatchStatus.WATCHING) },
    )
    runBlocking { repository.setSeenBulk(seasons.flatMap { it.episodes }.take(alreadySeen).map { it.id }, seen = true) }
    val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeEpisodeCatalogSource(mapOf(mini to seasons) + extras.mapValues { (id, _) -> listOf(extraSeason(id)) })), InMemoryEpisodeCatalogStore())
    val flags = FakeFeatureFlags().apply {
        this.tickAnimations.value = tickAnimations
        animationsEnabled.value = animations
    }
    val watchNext = ProgressViewModel(
        WatchNextUseCase({ collectionApi }, ObserveSeenEpisodesUseCase(repository), ObserveLastWatchedAtUseCase(repository), cache, FakeClock(1_000L * 86_400_000L)),
        TickEpisodeUseCase(ToggleEpisodeSeenUseCase(repository), CompletionDetector.None),
        cache,
        flags,
    )
    val upcomingCache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeUpcomingCatalogSource(emptyMap())), InMemoryEpisodeCatalogStore())
    val upcoming = UpcomingViewModel(FakeUpcomingCollectionApi(emptyList()), upcomingCache, FakeClock(0L))
    MuvissTheme(darkTheme = false) {
        ProgressScreen(watchNextViewModel = watchNext, upcomingViewModel = upcoming, onOpenDetail = {}, onOpenCoWatch = null)
    }
}
