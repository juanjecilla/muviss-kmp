@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.layout.ScreenInsets
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.TestSafeAreaInsets
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.GenresUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Search is the screen the inset bug was reported on: its search pill sat
 * under the camera cutout, because the shared root applied no insets and this
 * screen is a bare `Column` rather than an M3 `Scaffold`.
 *
 * Insets are injected, since Skiko reports none. That makes this a test of
 * whether the screen *responds* to a safe area — which is the part that was
 * broken. Whether Android reports the cutout is a device check.
 */
class SearchScreenInsetsTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_search_pill_clears_the_unsafe_area() = runComposeUiTest {
        setContent { SearchUnderTest(TestSafeAreaInsets) }
        waitForIdle()

        // 48dp of safe area on top of the unpadded baseline below. The 48dp
        // difference between these two tests is the assertion; the baseline is
        // the Column's 12dp plus the pill's own 12dp of inner padding.
        onNodeWithText(PLACEHOLDER).assertTopPositionInRootIsEqualTo(48.dp + PILL_BASELINE_TOP)
    }

    @Test
    fun without_a_safe_area_the_pill_sits_at_the_top() = runComposeUiTest {
        setContent { SearchUnderTest(NO_INSETS) }
        waitForIdle()

        onNodeWithText(PLACEHOLDER).assertTopPositionInRootIsEqualTo(PILL_BASELINE_TOP)
    }

    @Test
    fun search_renders_below_the_safe_area() = runComposeUiTest {
        setContent { SearchUnderTest(TestSafeAreaInsets) }
        waitForIdle()

        assertMatchesGolden("search-screen-insets")
    }

    private companion object {
        const val PLACEHOLDER = "Search movies & TV"

        /** Where the pill's text sits with no safe area: 12dp Column + 12dp pill. */
        val PILL_BASELINE_TOP = 24.dp
        val NO_INSETS = WindowInsets(left = 0.dp, top = 0.dp, right = 0.dp, bottom = 0.dp)
    }
}

@Composable
private fun SearchUnderTest(insets: WindowInsets) {
    val repo = FakeRepo(
        movieGenresResult = Result.success(listOf(Genre("28", "Action"), Genre("12", "Adventure"))),
        tvGenresResult = Result.success(listOf(Genre("10759", "Action & Adventure"))),
        discoverResult = { type, page, _ ->
            val id = if (type == MediaType.MOVIE) MediaId.tmdbMovie("1") else MediaId.tmdbTv("2")
            val title = if (type == MediaType.MOVIE) "A Movie" else "A Show"
            Result.success(PagedResult(listOf(MediaSummary(id, title)), page, page))
        },
    )
    val viewModel = SearchViewModel(
        SearchMediaUseCase(repo),
        DiscoverMediaUseCase(repo),
        GenresUseCase(repo),
        RecommendationsUseCase(repo),
        FakeSearchCollectionApi(),
        FakeTriageApi(),
    )
    // Pinned, never left to default: `MuvissTheme`'s `darkTheme` reads
    // `isSystemInDarkTheme()`, i.e. the *host's* setting, so a golden recorded
    // on a machine in dark mode compares against a light render on CI and every
    // pixel moves. See `GoldenSurface`.
    MuvissTheme(darkTheme = false) {
        GoldenSurface {
            ScreenInsets(insets = insets) {
                SearchScreen(viewModel = viewModel, onOpenDetail = {}, onOpenTriage = {})
            }
        }
    }
}
