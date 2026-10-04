@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.text

import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.generated.resources.Res
import com.codingpit.muviss.core.designsystem.generated.resources.action_retry
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compose Resources picks `values-<lang>` from the JVM default locale, so these
 * tests pin it explicitly — the host machine's language would otherwise decide
 * which assertion passes, the same trap as the host's dark-mode setting.
 */
class UiTextTest {

    private fun <T> withLocale(tag: String, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(tag))
        return try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun a_resource_renders_in_english_by_default() = withLocale("en-US") {
        runComposeUiTest {
            setContent { MuvissTheme(darkTheme = true) { Text(UiText.Resource(Res.string.action_retry).resolve()) } }
            onNodeWithText("Retry").assertExists()
        }
    }

    @Test
    fun a_resource_renders_in_spanish_under_a_spanish_locale() = withLocale("es-ES") {
        runComposeUiTest {
            setContent { MuvissTheme(darkTheme = true) { ErrorState(message = "x", onRetry = {}) } }
            onNodeWithText("Reintentar").assertExists()
        }
    }

    @Test
    fun any_spanish_region_falls_back_to_values_es() = withLocale("es-MX") {
        runComposeUiTest {
            setContent { MuvissTheme(darkTheme = true) { ErrorState(message = "x", onRetry = {}) } }
            onNodeWithText("Reintentar").assertExists()
        }
    }

    @Test
    fun raw_text_is_never_translated() = withLocale("es-ES") {
        runComposeUiTest {
            setContent { MuvissTheme(darkTheme = true) { Text(UiText.Raw("Breaking Bad").resolve()) } }
            onNodeWithText("Breaking Bad").assertExists()
        }
    }

    @Test
    fun resolve_async_matches_the_composable_lookup() = withLocale("es-ES") {
        runTest {
            assertEquals("Reintentar", UiText.Resource(Res.string.action_retry).resolveAsync())
            assertEquals("Breaking Bad", UiText.Raw("Breaking Bad").resolveAsync())
        }
    }
}
