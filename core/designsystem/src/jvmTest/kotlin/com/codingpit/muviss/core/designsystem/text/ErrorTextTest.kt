package com.codingpit.muviss.core.designsystem.text

import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** EPIC 31 (#74): network failures read in the user's language, and raw exception text never does. */
class ErrorTextTest {

    private val original = Locale.getDefault()

    @AfterTest
    fun restore() = Locale.setDefault(original)

    @Test
    fun `a metadata error resolves to its own copy in English`() = runTest {
        assertEquals(MetadataError.Offline().userMessage, MetadataError.Offline().toUiText(UiText.Raw("fallback")).resolveAsync())
    }

    @Test
    fun `and in Spanish under a Spanish locale`() = runTest {
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        assertEquals(
            "Parece que no tienes conexión. Compruébala e inténtalo de nuevo.",
            MetadataError.Offline().toUiText(UiText.Raw("fallback")).resolveAsync(),
        )
    }

    @Test
    fun `a rejected credential does not read as being offline`() = runTest {
        // #258: it used to share the offline wording, which is how #197 hid.
        val text = MetadataError.Unauthorized().toUiText(UiText.Raw("fallback")).resolveAsync()
        assertEquals(MetadataError.Unauthorized().userMessage, text)
        assertEquals(false, text.contains("reach") || text.contains("offline") || text.contains("try again", ignoreCase = true))
    }

    @Test
    fun `anything else is the fallback, never its message`() = runTest {
        val leaky = IllegalStateException("https://api.themoviedb.org/3?api_key=SECRET")
        assertEquals("fallback", leaky.toUiText(UiText.Raw("fallback")).resolveAsync())
    }
}
