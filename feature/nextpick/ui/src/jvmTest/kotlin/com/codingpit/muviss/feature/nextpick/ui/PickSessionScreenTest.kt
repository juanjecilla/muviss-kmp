@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.nextpick.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.layout.ScreenInsets
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.TestSafeAreaInsets
import com.codingpit.muviss.feature.nextpick.api.NextPickPreferences
import com.codingpit.muviss.feature.nextpick.api.ResultMode
import com.codingpit.muviss.feature.nextpick.api.SessionLength
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Pick Session screen (EPIC 51, #550): the question, its skip and back, the step line, the offline line, and the
 * insets it must clear. Driven through the real [PickSessionScreen] over the same engine and actions as
 * [PickSessionViewModelTest].
 */
class PickSessionScreenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_first_question_offers_skip_and_back_leaves_the_screen() = runComposeUiTest {
        var left = 0
        setContent { ScreenUnderTest(context = PickContext(length = SessionLength.STANDARD), onLeave = { left++ }) }
        waitForIdle()

        onNodeWithText("Skip").assertExists()
        onNodeWithContentDescription("Back").performClick()

        assertEquals(1, left)
    }

    @Test
    fun skipping_a_question_moves_to_the_next_step() = runComposeUiTest {
        setContent { ScreenUnderTest(context = PickContext(length = SessionLength.STANDARD)) }
        waitForIdle()
        onNodeWithText("Question 1 of 4").assertExists()

        onNodeWithText("Skip").performClick()
        waitForIdle()

        onNodeWithText("Question 2 of 4").assertExists()
    }

    @Test
    fun answering_a_question_chip_moves_to_the_next_step() = runComposeUiTest {
        setContent { ScreenUnderTest(context = PickContext(length = SessionLength.STANDARD)) }
        waitForIdle()
        onNodeWithText("Where should it come from?").assertExists()

        // "Either" leaves the two-candidate pool alone, so the step advance is the only thing this asserts.
        onNodeWithText("Either").performClick()
        waitForIdle()

        onNodeWithText("Question 2 of 4").assertExists()
        onNodeWithText("Where should it come from?").assertDoesNotExist()
    }

    @Test
    fun offline_the_question_says_only_the_list_is_searched() = runComposeUiTest {
        setContent { ScreenUnderTest(context = PickContext(length = SessionLength.STANDARD, online = false)) }
        waitForIdle()

        onNodeWithText("You're offline, so only your list is searched.").assertExists()
    }

    @Test
    fun the_screen_clears_the_safe_area_the_shell_pads_for_it() {
        // The shell pads the NavHost, and the screen's own Scaffold adds none: so a safe area moves the title down.
        val padded = titleTopWith(TestSafeAreaInsets)
        val unpadded = titleTopWith(WindowInsets(0, 0, 0, 0))

        assertTrue(padded > unpadded, "the title should sit lower under a safe area: padded $padded, unpadded $unpadded")
    }

    private fun titleTopWith(insets: WindowInsets): Float {
        var top = 0f
        runComposeUiTest {
            setContent {
                MuvissTheme(darkTheme = false) {
                    ScreenInsets(insets = insets) {
                        PickSessionScreen(viewModelFor(PickContext(length = SessionLength.STANDARD)), onLeave = {}, onOpenDeck = {})
                    }
                }
            }
            waitForIdle()
            top = onNodeWithText("What's next?").fetchSemanticsNode().boundsInRoot.top
        }
        return top
    }
}

/** The defaults a person starts with: a standard session, one pick at a time. */
private class DefaultPreferences : NextPickPreferences {
    override val sessionLength: Flow<SessionLength> = flowOf(SessionLength.STANDARD)
    override val resultMode: Flow<ResultMode> = flowOf(ResultMode.ONE_PICK)

    override suspend fun setSessionLength(length: SessionLength) = Unit

    override suspend fun setResultMode(mode: ResultMode) = Unit
}

/** A session over a small pool (One Pick, the Settings default), as [PickSessionViewModelTest] builds one. */
internal fun viewModelFor(context: PickContext, pool: List<com.codingpit.muviss.feature.nextpick.domain.PickCandidate> = listOf(candidate("1"), candidate("2"))) = PickSessionViewModel(context = { context }, engine = engineOver(pool), actions = RecordingNextPickActions(), preferences = DefaultPreferences())

@Composable
private fun ScreenUnderTest(context: PickContext, onLeave: () -> Unit = {}) {
    MuvissTheme(darkTheme = false) {
        PickSessionScreen(viewModelFor(context), onLeave = onLeave, onOpenDeck = {})
    }
}
