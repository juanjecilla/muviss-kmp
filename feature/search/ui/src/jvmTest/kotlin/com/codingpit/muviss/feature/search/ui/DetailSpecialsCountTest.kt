@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The Specials season's "N unseen" note and the primary button's answer with the setting off (#938, #952). The note
 * counts only aired, unseen specials, whatever the setting says. The test JVM runs in en-US (CLAUDE.md), so labels are
 * asserted as their English text.
 */
class DetailSpecialsCountTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_specials_header_counts_only_aired_unseen_specials() = runComposeUiTest {
        // One aired and seen, one aired and unseen, one not yet aired and unseen: only the middle one is "unseen".
        setContent {
            DetailUnderTest(
                details = showWith(specials = listOf(aired(0, 1), aired(0, 2), unaired(0, 3))),
                seen = setOf(EpisodeId(show, 0, 1)),
                countSpecials = flowOf(false),
            )
        }
        waitForIdle()

        onAllNodesWithText("unseen", substring = true, useUnmergedTree = true).assertCountEquals(1)
        onNodeWithText("1 unseen", substring = false, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun the_specials_header_says_nothing_when_every_aired_special_is_seen() = runComposeUiTest {
        setContent {
            DetailUnderTest(
                details = showWith(specials = listOf(aired(0, 1), unaired(0, 2))),
                seen = setOf(EpisodeId(show, 0, 1)),
                countSpecials = flowOf(false),
            )
        }
        waitForIdle()

        onAllNodesWithText("unseen", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun with_specials_not_counted_the_primary_button_reads_caught_up_over_an_unseen_aired_special() = runComposeUiTest {
        setContent {
            DetailUnderTest(
                details = showWith(specials = listOf(aired(0, 1))),
                seen = setOf(EpisodeId(show, 1, 1), EpisodeId(show, 1, 2)),
                countSpecials = flowOf(false),
            )
        }
        waitForIdle()

        onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("Caught up", substring = true)
        onAllNodesWithText(SPECIAL_UNSEEN_LABEL, substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun with_specials_counted_the_primary_button_reads_special_unseen_over_the_same_special() = runComposeUiTest {
        setContent {
            DetailUnderTest(
                details = showWith(specials = listOf(aired(0, 1))),
                seen = setOf(EpisodeId(show, 1, 1), EpisodeId(show, 1, 2)),
                countSpecials = flowOf(true),
            )
        }
        waitForIdle()

        onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains(SPECIAL_UNSEEN_LABEL, substring = true)
    }
}

private const val SPECIAL_UNSEEN_LABEL = "Special unseen"

/** Epoch day 20_000 is "today". */
private const val TODAY_EPOCH_DAY = 20_000L

private class SpecialsClock : AppClock {
    override fun nowEpochMs(): Long = TODAY_EPOCH_DAY * 86_400_000L
}

private val show = MediaId.tmdbTv("5151")

private fun aired(season: Int, number: Int) = Episode(EpisodeId(show, season, number), season, number, "S$season E$number", airDateEpochDay = TODAY_EPOCH_DAY - 30)

private fun unaired(season: Int, number: Int) = Episode(EpisodeId(show, season, number), season, number, "S$season E$number", airDateEpochDay = TODAY_EPOCH_DAY + 30)

/** Two regular episodes (both aired), and the given specials season. */
private fun showWith(specials: List<Episode>) = MediaDetails(
    summary = MediaSummary(id = show, title = "Specials Count", year = 2025, overview = "A show.", rating = 7.0),
    genres = listOf("Drama"),
    seasons = listOf(
        Season(0, "Specials", specials),
        Season(1, "Season 1", listOf(aired(1, 1), aired(1, 2))),
    ),
)

@Composable
private fun DetailUnderTest(details: MediaDetails, seen: Set<EpisodeId>, countSpecials: Flow<Boolean>) {
    val repo = FakeDetailRepo(details = details)
    val collection = FakeCollectionApi().apply { runBlocking { add(details) } }
    val progress = FakeProgressApi().also { fake ->
        runBlocking { seen.forEach { fake.setEpisodeSeen(it, true) } }
    }
    val viewModel = DetailViewModel(
        show,
        MediaDetailUseCase(repo),
        DetailPeers(collection, progress, FakeTriageApi(), FakeMutingApi()),
        WatchProvidersUseCase(repo),
        MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
        SpecialsClock(),
        countSpecials = countSpecials,
    )
    MuvissTheme(darkTheme = false) {
        GoldenSurface {
            DetailScreen(viewModel = viewModel, onBack = {}, onOpenDetail = {}, onOpenEpisode = {})
        }
    }
}
