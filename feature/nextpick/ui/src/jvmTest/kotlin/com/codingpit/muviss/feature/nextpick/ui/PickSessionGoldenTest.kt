@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.nextpick.ui

import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.TestSafeAreaInsets
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.feature.nextpick.api.SessionLength
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The Pick Session's first question and the "What's next?" completion sheet's content (EPIC 51, #550), in both themes. The
 * session frame is inside [TestSafeAreaInsets], so the golden shows the screen where the shell puts it. Both
 * frames pass `darkTheme` explicitly, as CLAUDE.md asks.
 */
class PickSessionGoldenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun first_question_light() = runComposeUiTest {
        setContent { SessionFrame(darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("nextpick-session-question", tolerance = SESSION_TOLERANCE)
    }

    @Test
    fun first_question_dark() = runComposeUiTest {
        setContent { SessionFrame(darkTheme = true) }
        waitForIdle()
        assertMatchesGolden("nextpick-session-question-dark", tolerance = SESSION_TOLERANCE)
    }

    @Test
    fun completion_sheet_light() = runComposeUiTest {
        setContent { SheetFrame(darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("nextpick-completion-sheet", tolerance = SESSION_TOLERANCE)
    }

    @Test
    fun completion_sheet_dark() = runComposeUiTest {
        setContent { SheetFrame(darkTheme = true) }
        waitForIdle()
        assertMatchesGolden("nextpick-completion-sheet-dark", tolerance = SESSION_TOLERANCE)
    }
}

@androidx.compose.runtime.Composable
private fun SessionFrame(darkTheme: Boolean) {
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface {
            com.codingpit.muviss.core.designsystem.layout.ScreenInsets(insets = TestSafeAreaInsets) {
                PickSessionScreen(viewModelFor(PickContext(length = SessionLength.STANDARD)), onLeave = {}, onOpenDeck = {})
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SheetFrame(darkTheme: Boolean) {
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface { CompletionSheetContent(onWhatsNext = {}, onNotNow = {}) }
    }
}

/** A question with its chips and a sheet with two buttons: text on flat surfaces, the same edge drift as the other screens. */
private const val SESSION_TOLERANCE = 0.04
