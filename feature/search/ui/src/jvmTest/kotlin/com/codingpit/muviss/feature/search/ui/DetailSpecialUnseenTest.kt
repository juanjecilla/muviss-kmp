@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
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
 * "Special unseen" (ADR 0037, #797): every regular episode is seen and an aired special is not. The primary button
 * names that state and, tapped, opens the Specials season and scrolls to its header. The Specials season is last
 * in the list here, so its header is off screen until the button brings it into view, which is the case the
 * anchor index exists for.
 *
 * The test JVM runs in en-US (CLAUDE.md), so the label is asserted as its English text.
 */
class DetailSpecialUnseenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_primary_button_reads_special_unseen_when_only_a_special_is_left() = runComposeUiTest {
        setContent { SpecialUnseenUnderTest() }
        waitForIdle()

        onNodeWithText(SPECIAL_UNSEEN_LABEL, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(PRIMARY_ACTION_TAG).assertIsEnabled()
    }

    @Test
    fun tapping_special_unseen_opens_and_scrolls_to_the_specials_season() = runComposeUiTest {
        setContent { SpecialUnseenUnderTest() }
        waitForIdle()
        // Off screen and collapsed: neither its header nor its special is composed yet.
        onAllNodesWithTag(SPECIALS_HEADER_TAG).assertCountEquals(0)

        onNodeWithTag(PRIMARY_ACTION_TAG).performClick()
        waitForIdle()

        onNodeWithTag(SPECIALS_HEADER_TAG).assertIsDisplayed()
        onNodeWithTag(SPECIALS_EPISODES_TAG).assertIsDisplayed()
    }
}

private const val SPECIAL_UNSEEN_LABEL = "Special unseen"
private const val SPECIALS_HEADER_TAG = "${SEASON_HEADER_TAG_PREFIX}0"
private const val SPECIALS_EPISODES_TAG = "${SEASON_EPISODES_TAG_PREFIX}0"

/** Epoch day 20_000 is "today". */
private const val TODAY_EPOCH_DAY = 20_000L

private class SpecialClock : AppClock {
    override fun nowEpochMs(): Long = TODAY_EPOCH_DAY * 86_400_000L
}

private val specialShow = MediaId.tmdbTv("5150")

private fun aired(season: Int, number: Int, name: String) = Episode(EpisodeId(specialShow, season, number), season, number, name, airDateEpochDay = TODAY_EPOCH_DAY - 30)

/** Six regular seasons, all aired and seen, then the Specials season with one aired, unseen special. */
private val regularSeasons = (1..6).map { Season(it, "Season $it", listOf(aired(it, 1, "Episode $it"))) }
private val specialsSeason = Season(0, "Specials", listOf(aired(0, 1, "Behind the scenes")))
private val specialDetails = MediaDetails(
    summary = MediaSummary(id = specialShow, title = "Special Show", year = 2025, overview = "A show.", rating = 7.0),
    genres = listOf("Drama"),
    seasons = regularSeasons + specialsSeason,
)

@Composable
private fun SpecialUnseenUnderTest() {
    val repo = FakeDetailRepo(details = specialDetails)
    val collection = FakeCollectionApi().apply { runBlocking { add(specialDetails) } }
    val progress = FakeProgressApi().also { fake ->
        runBlocking { regularSeasons.forEach { season -> season.episodes.forEach { fake.setEpisodeSeen(it.id, true) } } }
    }
    val viewModel = DetailViewModel(
        specialShow,
        MediaDetailUseCase(repo),
        DetailPeers(collection, progress, FakeTriageApi(), FakeMutingApi()),
        WatchProvidersUseCase(repo),
        MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
        SpecialClock(),
    )
    MuvissTheme(darkTheme = false) {
        GoldenSurface {
            DetailScreen(viewModel = viewModel, onBack = {}, onOpenDetail = {}, onOpenEpisode = {})
        }
    }
}
