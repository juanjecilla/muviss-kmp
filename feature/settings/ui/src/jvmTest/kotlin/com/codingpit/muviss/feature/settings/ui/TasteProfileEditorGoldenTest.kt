@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
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

/**
 * The Taste Profile editor (EPIC 51, #402, #587) with a realistic profile, in both themes: a liked movie genre, a
 * muted TV genre drawn disabled, liked and unliked services and formats. Renders the stateless
 * [TasteProfileContent], so the frame is the editor's own and nothing is loaded.
 *
 * `darkTheme` is passed explicitly, as CLAUDE.md asks; the Locale is pinned to US like [TasteProfileScreenTest].
 */
class TasteProfileEditorGoldenTest {

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

    private fun option(kind: TasteKind, value: String, label: String, liked: Boolean = false, muted: Boolean = false) = TasteOption(TasteItem(kind, value, label), liked = liked, muted = muted)

    private val profile = TasteProfileUiState(
        loading = false,
        movieGenres = listOf(
            option(TasteKind.GENRE, "movie:28", "Action", liked = true),
            option(TasteKind.GENRE, "movie:35", "Comedy"),
            option(TasteKind.GENRE, "movie:27", "Horror", muted = true),
        ),
        tvGenres = listOf(
            option(TasteKind.GENRE, "tv:18", "Drama", liked = true),
            option(TasteKind.GENRE, "tv:10767", "Talk", muted = true),
        ),
        formats = listOf(
            option(TasteKind.FORMAT, "MOVIE", "MOVIE", liked = true),
            option(TasteKind.FORMAT, "SERIES", "SERIES"),
            option(TasteKind.FORMAT, "LIMITED_SERIES", "LIMITED_SERIES"),
        ),
        services = listOf(
            option(TasteKind.SERVICE, "8", "Netflix", liked = true),
            option(TasteKind.SERVICE, "337", "Disney+"),
            option(TasteKind.SERVICE, "9", "Amazon Prime Video"),
        ),
        languages = listOf(
            option(TasteKind.LANGUAGE, "en", "English", liked = true),
            option(TasteKind.LANGUAGE, "es", "Español"),
        ),
        companies = listOf(
            option(TasteKind.COMPANY, "ALONE", "ALONE"),
            option(TasteKind.COMPANY, "FAMILY", "FAMILY", liked = true),
        ),
    )

    @Test
    fun taste_profile_editor_light() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = false) { GoldenSurface { TasteProfileContent(profile, onToggle = {}) } } }
        waitForIdle()
        assertMatchesGolden("taste-profile-editor", tolerance = EDITOR_TOLERANCE)
    }

    @Test
    fun taste_profile_editor_dark() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { GoldenSurface { TasteProfileContent(profile, onToggle = {}) } } }
        waitForIdle()
        assertMatchesGolden("taste-profile-editor-dark", tolerance = EDITOR_TOLERANCE)
    }
}

/**
 * Chips of text on a flow layout: the same text-edge drift as the Settings goldens, so the same tolerance.
 * CI measures the Ubuntu drift; a failure reports the share.
 */
private const val EDITOR_TOLERANCE = 0.04
