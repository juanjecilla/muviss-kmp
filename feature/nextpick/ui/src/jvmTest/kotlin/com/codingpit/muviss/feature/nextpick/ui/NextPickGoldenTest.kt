@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.nextpick.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.feature.nextpick.domain.NextPickResultMode
import com.codingpit.muviss.feature.nextpick.domain.PickReason
import com.codingpit.muviss.feature.nextpick.domain.RankedPick
import kotlin.test.Test

/**
 * The Top Picks and Deck layouts, each in both themes. `darkTheme` is passed explicitly: a golden recorded on a
 * machine in dark mode must not fail on CI (CLAUDE.md, screenshot tests).
 */
class NextPickGoldenTest {

    private val picks = (1..3).map { RankedPick(candidate(it.toString()), score = 1.0, reason = PickReason.FitsWhatYouAsked) }

    private fun state(mode: NextPickResultMode) = PickSessionUiState.Result(mode = mode, picks = picks, offline = false)

    private val noop = PickResultCallbacks(onAdd = {}, onNotForMe = {}, onSnooze = {}, onAnother = {}, onOpenDeck = {})

    @Test
    fun top_picks_light() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = false) {
                GoldenSurface { TopPicksPresentation.Content(state(NextPickResultMode.TOP_PICKS), noop, Modifier.fillMaxSize()) }
            }
        }
        assertMatchesGolden("nextpick-top-picks")
    }

    @Test
    fun top_picks_dark() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                GoldenSurface { TopPicksPresentation.Content(state(NextPickResultMode.TOP_PICKS), noop, Modifier.fillMaxSize()) }
            }
        }
        assertMatchesGolden("nextpick-top-picks-dark")
    }

    @Test
    fun deck_light() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = false) {
                GoldenSurface { DeckPresentation.Content(state(NextPickResultMode.DECK), noop, Modifier.fillMaxSize()) }
            }
        }
        assertMatchesGolden("nextpick-deck")
    }
}
