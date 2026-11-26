@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.onboarding.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.Res
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_taste_movie_genre_878
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_taste_tv_genre_10765
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test

/** The bundled genre names render in each language (#807). The test JVM is pinned to en-US; Spanish is set per test. */
class BundledGenreNamesTest {

    private val original = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(original)

    @Test
    fun a_bundled_genre_name_renders_in_english() = runComposeUiTest {
        Locale.setDefault(Locale.forLanguageTag("en-US"))
        renderProbes()
        onNodeWithTag(MOVIE_PROBE).assertTextEquals("Science Fiction")
        onNodeWithTag(TV_PROBE).assertTextEquals("Sci-Fi & Fantasy")
    }

    @Test
    fun a_bundled_genre_name_renders_in_spanish() = runComposeUiTest {
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        renderProbes()
        onNodeWithTag(MOVIE_PROBE).assertTextEquals("Ciencia ficción")
        onNodeWithTag(TV_PROBE).assertTextEquals("Sci-Fi y Fantasía")
    }

    private fun androidx.compose.ui.test.ComposeUiTest.renderProbes() = setContent {
        MuvissTheme(darkTheme = false) {
            Text(stringResource(Res.string.onboarding_taste_movie_genre_878), modifier = Modifier.testTag(MOVIE_PROBE))
            Text(stringResource(Res.string.onboarding_taste_tv_genre_10765), modifier = Modifier.testTag(TV_PROBE))
        }
    }

    private companion object {
        const val MOVIE_PROBE = "genre-name-movie-probe"
        const val TV_PROBE = "genre-name-tv-probe"
    }
}
