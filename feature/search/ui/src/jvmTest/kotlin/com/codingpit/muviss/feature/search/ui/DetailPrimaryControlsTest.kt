@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
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
 * The two Detail controls that say "nothing to do" in words (#783, #751): "Mark whole show as seen" is off with its
 * reason on a show nothing has aired for, and the primary button reads Loading, not a guessed action, while progress
 * is still arriving. Both are pinned here so a change to either shows up as a failing test, not as a silent no-op.
 *
 * The test JVM runs in en-US (CLAUDE.md), so the reason is asserted as its English text.
 */
class DetailPrimaryControlsTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun mark_whole_show_is_disabled_with_its_reason_when_nothing_has_aired() = runComposeUiTest {
        setContent { DetailUnderTest(notAiredShow()) }
        waitForIdle()

        onNodeWithTag(DETAIL_LIST_TAG).performScrollToKey("seasonsHeader")
        onNodeWithTag(MARK_SHOW_TAG).assertIsNotEnabled()
        onNodeWithText(NOTHING_AIRED_REASON).assertTextEquals(NOTHING_AIRED_REASON)
    }

    @Test
    fun mark_whole_show_is_enabled_and_gives_no_reason_once_an_episode_has_aired() = runComposeUiTest {
        setContent { DetailUnderTest(airedShow()) }
        waitForIdle()

        onNodeWithTag(DETAIL_LIST_TAG).performScrollToKey("seasonsHeader")
        onNodeWithTag(MARK_SHOW_TAG).assertIsEnabled()
        onAllNodesWithText(NOTHING_AIRED_REASON).assertCountEquals(0)
    }

    @Test
    fun the_primary_button_reads_loading_until_progress_has_reported() = runComposeUiTest {
        setContent { DetailUnderTest(airedShow(), progressReady = false) }
        waitForIdle()

        onNodeWithTag(PRIMARY_ACTION_TAG).assertIsNotEnabled()
        onNodeWithTag(PRIMARY_ACTION_TAG).assertTextEquals(LOADING_LABEL)
    }
}

private const val NOTHING_AIRED_REASON = "Nothing has aired yet"
private const val LOADING_LABEL = "Loading…"

/** Epoch day 20_000 is "today". */
private const val TODAY_EPOCH_DAY = 20_000L

private class ControlsClock : AppClock {
    override fun nowEpochMs(): Long = TODAY_EPOCH_DAY * 86_400_000L
}

private val controlsShow = MediaId.tmdbTv("4242")

private fun showWith(vararg episodes: Episode) = MediaDetails(
    summary = MediaSummary(id = controlsShow, title = "Controls Show", year = 2025, overview = "A show.", rating = 7.0),
    genres = listOf("Drama"),
    seasons = listOf(Season(1, "Season 1", episodes.toList())),
)

/** Every episode premieres after today: nothing has aired. */
private fun notAiredShow() = showWith(
    Episode(EpisodeId(controlsShow, 1, 1), 1, 1, "Premiere", airDateEpochDay = TODAY_EPOCH_DAY + 30),
    Episode(EpisodeId(controlsShow, 1, 2), 1, 2, "Second", airDateEpochDay = TODAY_EPOCH_DAY + 37),
)

/** The first episode aired a while ago and is unseen, so the show has an aired episode to mark. */
private fun airedShow() = showWith(
    Episode(EpisodeId(controlsShow, 1, 1), 1, 1, "Premiere", airDateEpochDay = TODAY_EPOCH_DAY - 30),
    Episode(EpisodeId(controlsShow, 1, 2), 1, 2, "Second", airDateEpochDay = TODAY_EPOCH_DAY + 7),
)

@Composable
private fun DetailUnderTest(details: MediaDetails, progressReady: Boolean = true) {
    val repo = FakeDetailRepo(details = details)
    // In the library, so the controls under test are the saved-title ones.
    val collection = FakeCollectionApi().apply { runBlocking { add(details) } }
    val progress = FakeProgressApi().apply { seenReady.value = progressReady }
    val viewModel = DetailViewModel(
        controlsShow,
        MediaDetailUseCase(repo),
        DetailPeers(collection, progress, FakeTriageApi(), FakeMutingApi()),
        WatchProvidersUseCase(repo),
        MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
        ControlsClock(),
    )
    MuvissTheme(darkTheme = false) {
        GoldenSurface {
            DetailScreen(viewModel = viewModel, onBack = {}, onOpenDetail = {}, onOpenEpisode = {})
        }
    }
}
