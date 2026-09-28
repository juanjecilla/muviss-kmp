@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A failed deck load, and a failed commit, both render the mapped copy on
 * Triage, and the raw exception text never reaches a node. Mirrors
 * [SearchScreenErrorCopyTest]'s shape (`feature/search/ui`); covers both
 * failure paths `TriageViewModelTest` already pins at the view-model level
 * ("load and commit").
 */
class TriageScreenErrorCopyTest {

    private val filmA = movie("1")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // --- load failure: rendered through ErrorState ---

    @Test
    fun a_load_time_metadata_error_shows_its_mapped_copy() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA))
        harness.source.failure = MetadataError.RateLimited(retryAfterSeconds = 30)

        setContent { MuvissTheme { TriageScreen(harness.viewModel(), onBack = {}, onOpenSkipped = {}, onOpenSnoozed = {}, onOpenDetail = {}) } }
        waitForIdle()

        onNodeWithText(MetadataError.RateLimited().userMessage).assertExists()
        onNodeWithText("Retry").assertExists()
    }

    @Test
    fun a_load_time_raw_exception_shows_generic_copy_and_never_its_message() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA))
        harness.source.failure = IllegalStateException("Unable to resolve host api.themoviedb.org?api_key=SECRET")

        setContent { MuvissTheme { TriageScreen(harness.viewModel(), onBack = {}, onOpenSkipped = {}, onOpenSnoozed = {}, onOpenDetail = {}) } }
        waitForIdle()

        onNodeWithText("Couldn't load more titles.").assertExists()
        onNodeWithText("SECRET", substring = true).assertDoesNotExist()
        onNodeWithText("themoviedb", substring = true).assertDoesNotExist()
    }

    // --- commit failure: rendered as a snackbar ---

    @Test
    fun a_commit_time_metadata_error_shows_its_mapped_copy_in_the_snackbar() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA))
        harness.details.failure = MetadataError.Offline()

        setContent { MuvissTheme { TriageScreen(harness.viewModel(), onBack = {}, onOpenSkipped = {}, onOpenSnoozed = {}, onOpenDetail = {}) } }
        waitForIdle()

        // "Later" commits filmA's decision; the details fetch behind it is
        // what fails.
        onNodeWithText("Later").performClick()
        waitForIdle()
        // The optimistic Undo snackbar claims the shared SnackbarHostState
        // first; dismiss it so the failed-commit snackbar (queued behind it)
        // gets its turn.
        onNode(hasDismissAction()).performSemanticsAction(SemanticsActions.Dismiss)
        waitForIdle()

        onNodeWithText(MetadataError.Offline().userMessage, substring = true).assertExists()
    }

    @Test
    fun a_commit_time_raw_exception_shows_generic_copy_and_never_its_message() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA))
        harness.details.failure = IllegalStateException("Unable to resolve host api.themoviedb.org?api_key=SECRET")

        setContent { MuvissTheme { TriageScreen(harness.viewModel(), onBack = {}, onOpenSkipped = {}, onOpenSnoozed = {}, onOpenDetail = {}) } }
        waitForIdle()

        onNodeWithText("Later").performClick()
        waitForIdle()
        onNode(hasDismissAction()).performSemanticsAction(SemanticsActions.Dismiss)
        waitForIdle()

        onNodeWithText("Couldn't save that one.", substring = true).assertExists()
        onNodeWithText("SECRET", substring = true).assertDoesNotExist()
        onNodeWithText("themoviedb", substring = true).assertDoesNotExist()
    }
}

/** Matches the snackbar container, so its optimistic Undo can be dismissed to let a queued snackbar take its place. */
private fun hasDismissAction(): SemanticsMatcher = SemanticsMatcher("has a Dismiss action") { it.config.contains(SemanticsActions.Dismiss) }
