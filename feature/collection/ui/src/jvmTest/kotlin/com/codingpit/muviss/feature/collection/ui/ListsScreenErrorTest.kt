@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.collection.domain.ListsUseCases
import com.codingpit.muviss.feature.collection.domain.MediaList
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** EPIC 30 (#73): a failed Lists load is an error with Retry, not a bare line of text. */
class ListsScreenErrorTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_failed_load_offers_retry_and_retry_shows_the_lists() = runComposeUiTest {
        val repository = FakeListsRepository(listOf(MediaList("1", "Marathon 2026", 0L, 0L)), failure = MetadataError.Offline())
        val vm = ListsViewModel(ListsUseCases(repository))

        setContent { MuvissTheme(darkTheme = false) { ListsScreen(vm, onOpenList = {}) } }
        waitForIdle()
        onNodeWithText(MetadataError.Offline().userMessage).assertExists()

        repository.failure = null
        onNodeWithText("Retry").performClick()
        waitForIdle()

        onNodeWithText("Marathon 2026").assertExists()
    }
}
