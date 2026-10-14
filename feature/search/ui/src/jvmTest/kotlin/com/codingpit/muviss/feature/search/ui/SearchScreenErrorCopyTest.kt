@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.GenresUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A failed TMDB call renders the mapped copy on Search, and the raw exception
 * text (which for a timeout carries the request URL, and so the v3 key) never
 * reaches a node. Before EPIC 27 a rate limit rendered as "No titles found"
 * and a timeout as Ktor's own message.
 */
class SearchScreenErrorCopyTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_rate_limit_shows_the_rate_limit_copy() = runComposeUiTest {
        setContent { SearchWithFailure(MetadataError.RateLimited(retryAfterSeconds = 30)) }
        waitForIdle()

        onNodeWithText(MetadataError.RateLimited().userMessage).assertExists()
        onNodeWithText("Retry").assertExists()
    }

    @Test
    fun an_offline_failure_shows_the_offline_copy() = runComposeUiTest {
        setContent { SearchWithFailure(MetadataError.Offline()) }
        waitForIdle()

        onNodeWithText(MetadataError.Offline().userMessage).assertExists()
    }

    @Test
    fun a_raw_exception_shows_generic_copy_and_never_its_message() = runComposeUiTest {
        val leak = "Request timeout has expired [url=https://api.themoviedb.org/3/genre/movie/list?api_key=SECRET]"
        setContent { SearchWithFailure(IllegalStateException(leak)) }
        waitForIdle()

        onNodeWithText("Something went wrong").assertExists()
        onNodeWithText("SECRET", substring = true).assertDoesNotExist()
        onNodeWithText("themoviedb", substring = true).assertDoesNotExist()
    }

    @Test
    fun the_search_field_says_what_it_is_for() = runComposeUiTest {
        // EPIC 31b (#164): a bare BasicTextField was announced as "Edit box".
        setContent { SearchWithFailure(failure = null) }
        waitForIdle()

        onNode(hasSetTextAction() and hasContentDescription("Search movies & TV")).assertExists()
    }

    @Test
    fun the_first_run_intro_shows_and_dismisses() = runComposeUiTest {
        // EPIC 30 (#73): the one-time Discover intro.
        var dismissed = 0
        setContent { SearchWithFailure(failure = null, introVisible = true, onIntroDismissed = { dismissed++ }) }
        waitForIdle()

        onNodeWithTag(DISCOVER_INTRO_TAG).assertExists()
        onNodeWithText("Got it").performClick()
        kotlin.test.assertEquals(1, dismissed)
    }
}

@Composable
private fun SearchWithFailure(failure: Throwable?, introVisible: Boolean = false, onIntroDismissed: () -> Unit = {}) {
    val repo = if (failure != null) FakeRepo(movieGenresResult = Result.failure(failure)) else FakeRepo()
    val viewModel = SearchViewModel(
        SearchMediaUseCase(repo),
        DiscoverMediaUseCase(repo),
        GenresUseCase(repo),
        RecommendationsUseCase(repo),
        FakeSearchCollectionApi(),
        FakeTriageApi(),
    )
    MuvissTheme(darkTheme = false) {
        SearchScreen(viewModel = viewModel, onOpenDetail = {}, onOpenTriage = {}, introVisible = introVisible, onIntroDismissed = onIntroDismissed)
    }
}
