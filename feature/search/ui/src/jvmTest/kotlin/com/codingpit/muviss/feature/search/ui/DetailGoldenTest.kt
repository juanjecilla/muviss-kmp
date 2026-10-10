@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.MoreLikeThisUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SimilarMediaUseCase
import com.codingpit.muviss.feature.search.domain.WatchProvidersUseCase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * What a show's Detail screen looks like, in both themes: the header a user
 * lands on, and the season list further down, where ticked and unticked
 * episodes sit side by side.
 *
 * Detail is a `LazyColumn` (EPIC 28), so the seasons frame scrolls the list by
 * key rather than by node: rows off screen are not composed.
 *
 * JVM-only: golden capture needs Skia. Posters and backdrops never load in a
 * test and `PosterImage` falls back to drawing the title. "Today" is a fixed
 * day, so aired-versus-unaired is a property of the fixture.
 *
 * The default tolerance, deliberately: Ubuntu's heavier glyphs have not been
 * measured against these frames yet. If CI fails it reports the measured
 * share, and that number, with headroom, belongs in a documented constant.
 */
class DetailGoldenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_saved_show_opens_on_its_header() = runComposeUiTest {
        showDetail(darkTheme = false)
        assertMatchesGolden("detail-show", tolerance = DETAIL_TOLERANCE)
    }

    @Test
    fun a_saved_show_opens_on_its_header_dark() = runComposeUiTest {
        showDetail(darkTheme = true)
        assertMatchesGolden("detail-show-dark", tolerance = DETAIL_TOLERANCE)
    }

    @Test
    fun the_season_list_shows_ticked_and_unticked_episodes() = runComposeUiTest {
        showDetail(darkTheme = false)
        onNodeWithTag(DETAIL_LIST_TAG).performScrollToKey("seasonsHeader")
        waitForIdle()
        assertMatchesGolden("detail-seasons", tolerance = DETAIL_TOLERANCE)
    }

    @Test
    fun the_season_list_shows_ticked_and_unticked_episodes_dark() = runComposeUiTest {
        showDetail(darkTheme = true)
        onNodeWithTag(DETAIL_LIST_TAG).performScrollToKey("seasonsHeader")
        waitForIdle()
        assertMatchesGolden("detail-seasons-dark", tolerance = DETAIL_TOLERANCE)
    }

    private fun ComposeUiTest.showDetail(darkTheme: Boolean) {
        setContent { DetailUnderTest(darkTheme) }
        waitForIdle()
    }
}

/** Epoch day 20_000 is 2024-10-04. Season 2 premieres that day, so its later episodes are unaired. */
private const val TODAY_EPOCH_DAY = 20_000L

private class FixedClock : AppClock {
    override fun nowEpochMs(): Long = TODAY_EPOCH_DAY * 86_400_000L
}

private val severance = MediaId.tmdbTv("95396")

private fun season(number: Int, names: List<String>, firstAirDay: Long) = Season(
    number = number,
    name = "Season $number",
    episodes = names.mapIndexed { index, name ->
        Episode(
            id = EpisodeId(severance, number, index + 1),
            seasonNumber = number,
            episodeNumber = index + 1,
            name = name,
            airDateEpochDay = firstAirDay + index * 7,
            runtimeMinutes = 55,
        )
    },
)

private val severanceDetails = MediaDetails(
    summary = MediaSummary(
        id = severance,
        title = "Severance",
        year = 2022,
        overview = "Mark leads a team of office workers whose memories have been surgically divided " +
            "between their work and personal lives.",
        rating = 8.4,
    ),
    genres = listOf("Drama", "Mystery", "Science Fiction"),
    runtimeMinutes = 55,
    productionStatus = ProductionStatus.RETURNING,
    seasons = listOf(
        season(1, listOf("Good News About Hell", "Half Loop", "In Perpetuity", "The You You Are", "The Grim Barbarity of Optics and Design"), firstAirDay = 19_040),
        season(2, listOf("Hello, Ms. Cobel", "Goodbye, Mrs. Selvig", "Who Is Alive?"), firstAirDay = TODAY_EPOCH_DAY),
    ),
)

@Composable
private fun DetailUnderTest(darkTheme: Boolean) {
    val repo = FakeDetailRepo(details = severanceDetails)
    // In the library, with season 1 watched through episode 3.
    val collection = FakeCollectionApi().apply { runBlocking { add(severanceDetails) } }
    val progress = FakeProgressApi().apply {
        runBlocking { (1..3).forEach { setEpisodeSeen(EpisodeId(severance, 1, it), seen = true) } }
    }
    val viewModel = DetailViewModel(
        severance,
        MediaDetailUseCase(repo),
        DetailPeers(collection, progress, FakeTriageApi()),
        WatchProvidersUseCase(repo),
        MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
        FixedClock(),
    )
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface {
            DetailScreen(viewModel = viewModel, onBack = {}, onOpenDetail = {}, onOpenEpisode = {})
        }
    }
}

/**
 * Wider than the 0.5% default: the header is mostly overview text and the
 * season list is a column of episode titles, and Linux rasterises the bundled
 * font heavier than macOS. Measured on `ubuntu-latest` against the macOS
 * recording (#278): header 1.724% / 1.707% (light / dark), seasons 1.875% /
 * 1.873%, glyph edges only. About 1.5x the larger.
 */
private const val DETAIL_TOLERANCE = 0.028
