@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.collection.domain.CollectionRefreshThrottle
import com.codingpit.muviss.feature.collection.domain.ListsUseCases
import com.codingpit.muviss.feature.collection.domain.ObserveCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.RefreshCollectionSnapshotsUseCase
import com.codingpit.muviss.feature.collection.domain.ToggleFavoriteUseCase
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A failed library load renders the mapped copy on Collection, and the raw
 * exception text never reaches a node. Mirrors [SearchScreenErrorCopyTest]'s
 * shape (`feature/search/ui`).
 */
class CollectionScreenErrorCopyTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_rate_limit_shows_the_rate_limit_copy() = runComposeUiTest {
        setContent { CollectionWithFailure(MetadataError.RateLimited(retryAfterSeconds = 30)) }
        waitForIdle()

        onNodeWithText(MetadataError.RateLimited().userMessage).assertExists()
        onNodeWithText("Retry").assertExists()
    }

    @Test
    fun an_offline_failure_shows_the_offline_copy() = runComposeUiTest {
        setContent { CollectionWithFailure(MetadataError.Offline()) }
        waitForIdle()

        onNodeWithText(MetadataError.Offline().userMessage).assertExists()
    }

    @Test
    fun a_raw_exception_shows_generic_copy_and_never_its_message() = runComposeUiTest {
        val leak = "Unable to resolve host api.themoviedb.org?api_key=SECRET"
        setContent { CollectionWithFailure(IllegalStateException(leak)) }
        waitForIdle()

        onNodeWithText("Something went wrong").assertExists()
        onNodeWithText("SECRET", substring = true).assertDoesNotExist()
        onNodeWithText("themoviedb", substring = true).assertDoesNotExist()
    }
}

private class NoopClock : AppClock {
    override fun nowEpochMs(): Long = 0L
}

@Composable
private fun CollectionWithFailure(failure: Throwable) {
    val repository = FakeCollectionRepository(emptyList(), failure = failure)
    // Pre-claims the automatic refresh so CollectionViewModel's init never
    // fires it: that refresh reads the same failing repository.observeAll()
    // and would surface the identical mapped copy a second time, as a
    // snackbar message rather than the ErrorState this test targets.
    val throttle = CollectionRefreshThrottle(NoopClock()).apply { recordRefresh() }
    val viewModel = CollectionViewModel(
        ObserveCollectionUseCase(repository),
        ToggleFavoriteUseCase(repository),
        RefreshCollectionSnapshotsUseCase(repository, NoopSnapshotSource()),
        throttle,
    )
    val listsViewModel = ListsViewModel(ListsUseCases(FakeListsRepository()))
    MuvissTheme(darkTheme = false) {
        CollectionScreen(viewModel = viewModel, listsViewModel = listsViewModel, onOpenDetail = {}, onOpenList = {})
    }
}
