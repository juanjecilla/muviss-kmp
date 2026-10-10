@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
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

/**
 * What Watch Next — the Progress tab's default view — looks like with three
 * shows at different points, in both themes: the row, its next episode and
 * its progress bar are the parts a theme change can quietly break.
 *
 * JVM-only: golden capture needs Skia. Posters never load in a test and
 * `PosterImage` falls back to drawing the title. "Today" is a fixed day, so
 * aired-versus-unaired is a property of the fixture, not of the calendar.
 *
 * The default tolerance, deliberately: Ubuntu's heavier glyphs have not been
 * measured against this frame yet. If CI fails it reports the measured share,
 * and that number, with headroom, belongs in a documented constant.
 */
class ProgressGoldenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun watch_next_lists_each_show_at_its_next_episode() = runComposeUiTest {
        setContent { WatchNextUnderTest(darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("progress-watch-next", tolerance = WATCH_NEXT_TOLERANCE)
    }

    @Test
    fun watch_next_lists_each_show_at_its_next_episode_dark() = runComposeUiTest {
        setContent { WatchNextUnderTest(darkTheme = true) }
        waitForIdle()
        assertMatchesGolden("progress-watch-next-dark", tolerance = WATCH_NEXT_TOLERANCE)
    }
}

/** Epoch day 20_000 is 2024-10-04; every fixture episode is dated before it. */
private const val TODAY_EPOCH_DAY = 20_000L

private val severance = MediaId.tmdbTv("95396")
private val theBear = MediaId.tmdbTv("136315")
private val shogun = MediaId.tmdbTv("126308")

private fun season(show: MediaId, number: Int, names: List<String>, firstAirDay: Long) = Season(
    number = number,
    name = "Season $number",
    episodes = names.mapIndexed { index, name ->
        Episode(
            id = EpisodeId(show, number, index + 1),
            seasonNumber = number,
            episodeNumber = index + 1,
            name = name,
            airDateEpochDay = firstAirDay + index * 7,
        )
    },
)

private val catalog = mapOf(
    severance to listOf(
        season(severance, 1, listOf("Good News About Hell", "Half Loop", "In Perpetuity", "The You You Are"), firstAirDay = 19_040),
    ),
    theBear to listOf(
        season(theBear, 1, listOf("System", "Hands", "Brigade", "Dogs"), firstAirDay = 19_160),
        season(theBear, 2, listOf("Beef", "Pasta", "Sundae", "Honeydew"), firstAirDay = 19_530),
    ),
    shogun to listOf(
        season(shogun, 1, listOf("Anjin", "Servants of Two Masters", "Tomorrow Is Tomorrow", "The Eightfold Fence"), firstAirDay = 19_772),
    ),
)

/** What the user has already ticked: one show barely started, one mid-season, one into its second season. */
private val ticked = listOf(
    EpisodeId(severance, 1, 1),
    EpisodeId(severance, 1, 2),
    EpisodeId(shogun, 1, 1),
) + (1..4).map { EpisodeId(theBear, 1, it) } + EpisodeId(theBear, 2, 1)

@Composable
private fun WatchNextUnderTest(darkTheme: Boolean) {
    val collectionApi = FakeCollectionApi(
        listOf(
            CollectionSummary(severance, "Severance", posterUrl = null, status = WatchStatus.WATCHING),
            CollectionSummary(theBear, "The Bear", posterUrl = null, status = WatchStatus.WATCHING),
            CollectionSummary(shogun, "Shōgun", posterUrl = null, status = WatchStatus.WATCHING),
        ),
    )
    val progressRepository = FakeProgressRepository().apply { runBlocking { setSeenBulk(ticked, seen = true) } }
    val clock = FakeClock(TODAY_EPOCH_DAY * 86_400_000L)
    val watchNextCache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeEpisodeCatalogSource(catalog)), InMemoryEpisodeCatalogStore())
    val watchNextViewModel = ProgressViewModel(
        WatchNextUseCase({ collectionApi }, ObserveSeenEpisodesUseCase(progressRepository), watchNextCache, clock),
        ToggleEpisodeSeenUseCase(progressRepository),
        watchNextCache,
    )
    val upcomingCache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeUpcomingCatalogSource(emptyMap())), InMemoryEpisodeCatalogStore())
    val upcomingViewModel = UpcomingViewModel(FakeUpcomingCollectionApi(emptyList()), upcomingCache, FakeUpcomingClock(0L))

    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface {
            ProgressScreen(watchNextViewModel = watchNextViewModel, upcomingViewModel = upcomingViewModel, onOpenDetail = {}, onOpenCoWatch = null)
        }
    }
}

/**
 * Wider than the 0.5% default for the same font-rasterising reason as
 * `TriageDeckGoldenTest.DECK_TOLERANCE`: posters carry most of the frame, but
 * each row's title and episode line is text. Measured on `ubuntu-latest`
 * against the macOS recording (#278): 0.756% light, 0.728% dark, glyph edges
 * only. About 1.5x that.
 */
private const val WATCH_NEXT_TOLERANCE = 0.012
