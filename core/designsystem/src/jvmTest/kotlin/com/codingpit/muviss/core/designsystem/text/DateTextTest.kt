package com.codingpit.muviss.core.designsystem.text

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Test JVMs are pinned to en-US (`muviss.kmp.compose`); the Spanish cases set the locale themselves. */
class DateTextTest {

    private val original = Locale.getDefault()

    @AfterTest
    fun restore() = Locale.setDefault(original)

    @Test
    fun `it formats a date readably`() = runTest {
        // 2024-02-29 is epoch day 19782.
        assertEquals("29 Feb 2024", dateTextAsync(19_782L))
        assertEquals("1 Jan 1970", dateTextAsync(0L))
    }

    @Test
    fun `december maps to the last month name`() = runTest {
        // 2023-12-25 is epoch day 19716.
        assertEquals("25 Dec 2023", dateTextAsync(19_716L))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a composition under a Spanish locale renders Spanish month names`() = runComposeUiTest {
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        setContent { Text(dateText(19_716L)) }
        onNodeWithText("25 dic 2023").assertExists()
    }
}
