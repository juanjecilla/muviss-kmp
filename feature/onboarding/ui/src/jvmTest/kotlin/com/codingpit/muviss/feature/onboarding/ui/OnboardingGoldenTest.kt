@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.onboarding.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.feature.settings.api.TmdbDataSource
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Every page of the introduction, in both themes, plus Spanish for the two pages with the most
 * copy (EPIC 47): a frame that overflows or clips in Spanish is the one a translation breaks.
 */
class OnboardingGoldenTest {

    private val original = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(original)

    private val all = pagesFor(canNotify = true, byokOffered = true, canBackUp = true)

    private fun state(page: OnboardingPage, source: TmdbDataSource = TmdbDataSource.MUVISS) = OnboardingUiState(pages = all, index = all.indexOf(page), keySource = source)

    private fun androidx.compose.ui.test.ComposeUiTest.golden(name: String, state: OnboardingUiState, darkTheme: Boolean, tolerance: Double = TOLERANCE) {
        setContent { Frame(state, darkTheme) }
        waitForIdle()
        assertMatchesGolden(name, tolerance = tolerance)
    }

    @Test fun welcome() = runComposeUiTest { golden("onboarding-welcome", state(OnboardingPage.WELCOME), false) }

    @Test fun welcome_dark() = runComposeUiTest { golden("onboarding-welcome-dark", state(OnboardingPage.WELCOME), true) }

    @Test fun library() = runComposeUiTest { golden("onboarding-library", state(OnboardingPage.LIBRARY), false) }

    @Test fun library_dark() = runComposeUiTest { golden("onboarding-library-dark", state(OnboardingPage.LIBRARY), true) }

    @Test fun watch_next() = runComposeUiTest { golden("onboarding-watch-next", state(OnboardingPage.WATCH_NEXT), false) }

    @Test fun watch_next_dark() = runComposeUiTest { golden("onboarding-watch-next-dark", state(OnboardingPage.WATCH_NEXT), true) }

    @Test fun discover() = runComposeUiTest { golden("onboarding-discover", state(OnboardingPage.DISCOVER), false) }

    @Test fun discover_dark() = runComposeUiTest { golden("onboarding-discover-dark", state(OnboardingPage.DISCOVER), true) }

    @Test fun import_page() = runComposeUiTest { golden("onboarding-import", state(OnboardingPage.IMPORT), false) }

    @Test fun import_page_dark() = runComposeUiTest { golden("onboarding-import-dark", state(OnboardingPage.IMPORT), true) }

    @Test fun notifications() = runComposeUiTest { golden("onboarding-notifications", state(OnboardingPage.NOTIFICATIONS), false) }

    @Test fun notifications_dark() = runComposeUiTest { golden("onboarding-notifications-dark", state(OnboardingPage.NOTIFICATIONS), true) }

    @Test fun key() = runComposeUiTest { golden("onboarding-key", state(OnboardingPage.TMDB_KEY), false) }

    @Test fun key_dark() = runComposeUiTest { golden("onboarding-key-dark", state(OnboardingPage.TMDB_KEY, TmdbDataSource.YOURS), true) }

    private val defaultsMuted = setOf(MutedGenreChoice.TALK, MutedGenreChoice.NEWS, MutedGenreChoice.REALITY)

    @Test fun muted_genres() = runComposeUiTest { golden("onboarding-muted-genres", state(OnboardingPage.MUTED_GENRES).copy(mutedGenres = defaultsMuted), false) }

    @Test fun muted_genres_dark() = runComposeUiTest { golden("onboarding-muted-genres-dark", state(OnboardingPage.MUTED_GENRES).copy(mutedGenres = defaultsMuted), true) }

    @Test
    fun muted_genres_in_spanish() = runComposeUiTest {
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        golden("onboarding-muted-genres-es", state(OnboardingPage.MUTED_GENRES).copy(mutedGenres = defaultsMuted), false, MUTED_GENRES_ES_TOLERANCE)
    }

    @Test fun done() = runComposeUiTest { golden("onboarding-done", state(OnboardingPage.DONE), false) }

    @Test fun done_dark() = runComposeUiTest { golden("onboarding-done-dark", state(OnboardingPage.DONE), true) }

    @Test
    fun notifications_in_spanish() = runComposeUiTest {
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        golden("onboarding-notifications-es", state(OnboardingPage.NOTIFICATIONS).copy(notificationsDenied = true), false)
    }

    @Test
    fun key_in_spanish() = runComposeUiTest {
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        golden("onboarding-key-es", state(OnboardingPage.TMDB_KEY, TmdbDataSource.YOURS), false)
    }
}

@Composable
private fun Frame(state: OnboardingUiState, darkTheme: Boolean) {
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface {
            OnboardingContent(
                state = state,
                onNext = {},
                onBack = {},
                onFinish = {},
                onOpenImport = {},
                onNotificationsToggled = {},
                onDeviceBackupToggled = {},
                onOpenSystemNotificationSettings = {},
                onSaveKey = { _, _ -> },
                onUseMuvissKey = {},
                onDismissKeyProblem = {},
            )
        }
    }
}

// Centred text blocks on a plain surface: glyph edges are all that can differ between macOS and
// Linux. Not measured on CI yet; widen to the measured share (with headroom) if it fails there.
private const val TOLERANCE = 0.03

/**
 * The Spanish Muted Genres page is the densest frame here: four two-line rows under a paragraph, so
 * Linux's heavier glyphs move more pixels than on any other page. Measured on `ubuntu-latest` against
 * the macOS recording (#389): 3.504%. About 1.4x that, still far below what a moved or missing row costs.
 */
private const val MUTED_GENRES_ES_TOLERANCE = 0.05
