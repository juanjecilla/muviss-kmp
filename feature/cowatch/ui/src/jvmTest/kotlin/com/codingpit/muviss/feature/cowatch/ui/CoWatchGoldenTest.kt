@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.cowatch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.feature.cowatch.api.CompanionState
import com.codingpit.muviss.feature.cowatch.api.Shortlist
import com.codingpit.muviss.feature.cowatch.api.ShortlistReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * What the two co-watch screens *look* like, which the behaviour tests in
 * [CompanionsScreenTest] and [ShortlistScreenTest] cannot see.
 *
 * Every case is recorded in both themes, because `MuvissTheme`'s `darkTheme`
 * defaults to `isSystemInDarkTheme()` — the host machine's setting — so a
 * golden recorded on a Mac in dark mode would fail on CI with nearly every
 * pixel moved (CLAUDE.md, `GoldenSurface`). Posters never load in a test, and
 * `PosterImage` falls back to drawing the title, so these are deterministic
 * without a network.
 *
 * Cases pinned here are the ones #130 called out specifically: the empty
 * Shortlist before either side has published (which is also the "their list
 * hasn't arrived yet" case — the two are the same screen), a populated
 * Shortlist with its per-row explanation lines, and a Companion in each of
 * the four [CompanionState] values in one screen — REVOKED included, whose
 * copy states the honest limit of unlinking and must not drift unnoticed.
 */
class CoWatchGoldenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun companions_in_every_link_state_light() = runComposeUiTest {
        setContent { CompanionsUnderTest(darkTheme = false) }
        assertMatchesGolden("companions-screen-links", tolerance = GOLDEN_TOLERANCE)
    }

    @Test
    fun companions_in_every_link_state_dark() = runComposeUiTest {
        setContent { CompanionsUnderTest(darkTheme = true) }
        assertMatchesGolden("companions-screen-links-dark", tolerance = GOLDEN_TOLERANCE)
    }

    @Test
    fun shortlist_before_either_side_has_published_light() = runComposeUiTest {
        setContent { ShortlistUnderTest(darkTheme = false, shortlist = Shortlist(items = emptyList(), companionPoolPublishedAtEpochMs = null)) }
        assertMatchesGolden("shortlist-pending", tolerance = GOLDEN_TOLERANCE)
    }

    @Test
    fun shortlist_before_either_side_has_published_dark() = runComposeUiTest {
        setContent { ShortlistUnderTest(darkTheme = true, shortlist = Shortlist(items = emptyList(), companionPoolPublishedAtEpochMs = null)) }
        assertMatchesGolden("shortlist-pending-dark", tolerance = GOLDEN_TOLERANCE)
    }

    @Test
    fun a_ranked_shortlist_light() = runComposeUiTest {
        setContent { ShortlistUnderTest(darkTheme = false, shortlist = rankedShortlist()) }
        assertMatchesGolden("shortlist-ranked", tolerance = GOLDEN_TOLERANCE)
    }

    @Test
    fun a_ranked_shortlist_dark() = runComposeUiTest {
        setContent { ShortlistUnderTest(darkTheme = true, shortlist = rankedShortlist()) }
        assertMatchesGolden("shortlist-ranked-dark", tolerance = GOLDEN_TOLERANCE)
    }

    private fun rankedShortlist() = Shortlist(
        items = listOf(
            shortlistItem(
                "1",
                title = "Paddington",
                runtimeMinutes = 95,
                reasons = setOf(ShortlistReason.BOTH_PINNED, ShortlistReason.NEITHER_STARTED),
            ),
            shortlistItem(
                "2",
                title = "The Before Trilogy",
                runtimeMinutes = 105,
                reasons = setOf(ShortlistReason.REVISIT),
            ),
        ),
        companionPoolPublishedAtEpochMs = 1_000L,
    )
}

@Composable
private fun CompanionsUnderTest(darkTheme: Boolean) {
    val api = FakeCoWatchApi(
        companions = listOf(
            companion("invited", CompanionState.INVITED),
            companion("awaiting", CompanionState.AWAITING_CONFIRMATION, localName = "Sam"),
            companion("active", CompanionState.ACTIVE, localName = "Jordan"),
            companion("revoked", CompanionState.REVOKED),
        ),
    )
    // Pinned, never left to default: see this file's KDoc and `GoldenSurface`.
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface {
            CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = {})
        }
    }
}

@Composable
private fun ShortlistUnderTest(darkTheme: Boolean, shortlist: Shortlist) {
    val api = FakeCoWatchApi().apply { shortlists.value = mapOf(COMPANION_ID to shortlist) }
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface {
            ShortlistScreen(COMPANION_ID, ShortlistViewModel(api), onBack = {}, onOpenDetail = {})
        }
    }
}

private const val COMPANION_ID = "pal"

/**
 * Wider than the 0.5% default: both screens are wall-to-wall body text (the
 * consent line, the per-row explanation, the four state sentences), and Linux
 * rasterises the bundled font heavier than macOS does — the same trade-off
 * `TriageDeckGoldenTest.DECK_TOLERANCE` documents, sized down slightly because
 * neither screen carries as much text as the search screen CLAUDE.md measures
 * at 2.15%.
 */
private const val GOLDEN_TOLERANCE = 0.015
