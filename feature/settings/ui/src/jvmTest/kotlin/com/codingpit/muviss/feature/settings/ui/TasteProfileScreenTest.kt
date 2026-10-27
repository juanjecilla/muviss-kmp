@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Taste Profile editor's rendering and its one rule the person can see: a muted genre is
 * shown disabled, not hidden, and tapping an ordinary chip reports that item to the ViewModel.
 * Runs on jvmTest only: Compose UI tests render through Skiko (see the CLAUDE.md note).
 */
class TasteProfileScreenTest {

    private val original = Locale.getDefault()

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        Locale.setDefault(original)
    }

    private val netflix = TasteOption(TasteItem(TasteKind.SERVICE, "8", "Netflix"), liked = false, muted = false)
    private val talk = TasteOption(TasteItem(TasteKind.GENRE, "tv:10767", "Talk"), liked = false, muted = true)
    private val action = TasteOption(TasteItem(TasteKind.GENRE, "movie:28", "Action"), liked = true, muted = false)

    private fun state(vararg tvGenres: TasteOption) = TasteProfileUiState(
        loading = false,
        tvGenres = tvGenres.toList(),
        services = listOf(netflix),
        movieGenres = listOf(action),
    )

    @Test
    fun a_muted_genre_is_shown_disabled_rather_than_hidden() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = false) { TasteProfileContent(state(talk), onToggle = {}) } }

        onNodeWithText("Talk").assertIsNotEnabled()
    }

    @Test
    fun an_ordinary_chip_is_enabled_and_reports_the_tap() = runComposeUiTest {
        var tapped: TasteOption? = null
        setContent { MuvissTheme(darkTheme = false) { TasteProfileContent(state(), onToggle = { tapped = it }) } }

        onNodeWithTag(tasteOptionTag(netflix)).assertIsEnabled().performClick()

        assertEquals(netflix, tapped)
    }

    @Test
    fun a_liked_genre_reports_the_opposite_state_when_tapped() = runComposeUiTest {
        var tapped: TasteOption? = null
        setContent { MuvissTheme(darkTheme = false) { TasteProfileContent(state(), onToggle = { tapped = it }) } }

        onNodeWithTag(tasteOptionTag(action)).performClick()

        assertEquals(true, tapped?.liked, "the chip reports what it is now; the ViewModel flips it")
    }
}
