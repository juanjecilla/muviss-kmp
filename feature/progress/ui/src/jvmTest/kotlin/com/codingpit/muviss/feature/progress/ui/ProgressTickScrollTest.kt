@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.runComposeUiTest
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

/**
 * Ticking a show makes it the one most recently watched, so Watch Next puts it
 * first. The list has to follow it there: a row that moves to index 0 while the
 * user is scrolled down lands above the viewport, and the tick would look like
 * the row had vanished.
 */
class ProgressTickScrollTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun ticking_a_row_far_down_brings_it_to_the_top() = runComposeUiTest {
        setContent { ManyShows() }
        onNodeWithTag(WATCH_NEXT_LIST_TAG).performScrollToKey(key(1))

        tick(1)

        topRowIs("Show 1")
    }

    @Test
    fun ticking_a_row_far_down_brings_it_to_the_top_with_motion_off() = runComposeUiTest {
        setContent { ManyShows(tickAnimations = false) }
        onNodeWithTag(WATCH_NEXT_LIST_TAG).performScrollToKey(key(1))

        tick(1)

        topRowIs("Show 1")
    }

    // Only the scroll: the row leaving is the collection's doing (the show's status turns
    // Watched), and FakeCollectionApi keeps the status it was given, so the row stays here.
    @Test
    fun catching_up_leaves_the_scroll_alone() = runComposeUiTest {
        setContent { ManyShows(lastEpisodeOnShow1 = true) }
        onNodeWithTag(WATCH_NEXT_LIST_TAG).performScrollToKey(key(1))

        tick(1)

        onNodeWithText("Show 40").assertDoesNotExist()
    }

    private fun ComposeUiTest.tick(show: Int) {
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Mark Show $show next episode watched").performClick()
        mainClock.advanceTimeBy(2_000)
    }

    private fun ComposeUiTest.topRowIs(title: String) {
        onNodeWithText(title).assertIsDisplayed()
        onNodeWithTag(WATCH_NEXT_LIST_TAG).onChildAt(0).assert(hasAnyDescendant(hasText(title)))
    }
}

private fun key(show: Int) = show(show).toString()

private fun show(n: Int) = MediaId.tmdbTv((1000 + n).toString())

private const val SHOWS = 40

/** [SHOWS] watching shows, far more than fit; Show 40 was watched last, Show 1 first, so Show 1 starts at the bottom. */
@Composable
private fun ManyShows(tickAnimations: Boolean = true, lastEpisodeOnShow1: Boolean = false) {
    val repository = FakeProgressRepository()
    val ids = (1..SHOWS).map(::show)
    val catalogs = ids.associateWith { id ->
        val count = if (lastEpisodeOnShow1 && id == show(1)) 2 else 3
        listOf(Season(1, "Season 1", (1..count).map { Episode(EpisodeId(id, 1, it), 1, it, "Episode $it", airDateEpochDay = 100L + it) }))
    }
    runBlocking {
        ids.forEach { id -> repository.setSeen(EpisodeId(id, 1, 1), true) }
    }
    val collectionApi = FakeCollectionApi(
        ids.mapIndexed { index, id -> CollectionSummary(id, "Show ${index + 1}", posterUrl = null, status = WatchStatus.WATCHING) },
    )
    val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeEpisodeCatalogSource(catalogs)), InMemoryEpisodeCatalogStore())
    val flags = FakeFeatureFlags().apply { this.tickAnimations.value = tickAnimations }
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
