@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.component.CELEBRATION_BURST_TAG
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.FakeFeatureFlags
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenEpisodesUseCase
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

    private fun ComposeUiTest.tapTick() {
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Mark", substring = true).performClick()
        mainClock.advanceTimeBy(100)
    }
}

private val mini = MediaId.tmdbTv("4242")

/** [count] aired episodes in season [number]. */
private fun season(number: Int, count: Int) = Season(
    number = number,
    name = "Season $number",
    episodes = (1..count).map { Episode(EpisodeId(mini, number, it), number, it, "Episode $it", airDateEpochDay = 100L + number * 10 + it) },
)

@Composable
private fun WatchNext(
    seasons: List<Season>,
    alreadySeen: Int,
    tickAnimations: Boolean = true,
    animations: Boolean = true,
    repository: FakeProgressRepository = FakeProgressRepository(),
) {
    val collectionApi = FakeCollectionApi(listOf(CollectionSummary(mini, "Mini", posterUrl = null, status = WatchStatus.WATCHING)))
    runBlocking { repository.setSeenBulk(seasons.flatMap { it.episodes }.take(alreadySeen).map { it.id }, seen = true) }
    val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeEpisodeCatalogSource(mapOf(mini to seasons))), InMemoryEpisodeCatalogStore())
    val flags = FakeFeatureFlags().apply {
        this.tickAnimations.value = tickAnimations
        animationsEnabled.value = animations
    }
    val watchNext = ProgressViewModel(
        WatchNextUseCase({ collectionApi }, ObserveSeenEpisodesUseCase(repository), cache, FakeClock(1_000L * 86_400_000L)),
        ToggleEpisodeSeenUseCase(repository),
        cache,
        flags,
    )
    val upcomingCache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeUpcomingCatalogSource(emptyMap())), InMemoryEpisodeCatalogStore())
    val upcoming = UpcomingViewModel(FakeUpcomingCollectionApi(emptyList()), upcomingCache, FakeClock(0L))
    MuvissTheme(darkTheme = false) {
        ProgressScreen(watchNextViewModel = watchNext, upcomingViewModel = upcoming, onOpenDetail = {}, onOpenCoWatch = null)
    }
}
