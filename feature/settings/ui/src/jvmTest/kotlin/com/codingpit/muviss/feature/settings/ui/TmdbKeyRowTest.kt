@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.component.TMDB_KEY_FIELD_TAG
import com.codingpit.muviss.core.designsystem.component.TMDB_KEY_SAVE_TAG
import com.codingpit.muviss.core.designsystem.component.TMDB_KEY_USE_MUVISS_TAG
import com.codingpit.muviss.core.designsystem.component.TmdbKeyProblem
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.settings.api.TmdbDataSource
import kotlin.test.Test
import kotlin.test.assertEquals

class TmdbKeyRowTest {

    private fun androidx.compose.ui.test.ComposeUiTest.row(
        state: TmdbKeyUiState,
        onSave: (String, () -> Unit) -> Unit = { _, _ -> },
        onUseMuviss: (() -> Unit) -> Unit = {},
    ) = setContent {
        MuvissTheme(darkTheme = false) {
            TmdbKeyRow(state, onSave = onSave, onUseMuviss = onUseMuviss, onDismiss = {})
        }
    }

    @Test
    fun the_row_says_whose_key_is_in_use() = runComposeUiTest {
        row(TmdbKeyUiState(visible = true, source = TmdbDataSource.YOURS))
        onNodeWithText("Your key").assertExists()
    }

    @Test
    fun saving_is_disabled_until_something_is_typed_and_hands_the_text_over() = runComposeUiTest {
        var saved: String? = null
        row(TmdbKeyUiState(visible = true), onSave = { raw, _ -> saved = raw })

        onNodeWithTag(TMDB_KEY_ROW_TAG).performClick()
        onNodeWithTag(TMDB_KEY_SAVE_TAG).assertIsNotEnabled()

        onNodeWithTag(TMDB_KEY_FIELD_TAG).performTextInput("a".repeat(32))
        onNodeWithTag(TMDB_KEY_SAVE_TAG).assertIsEnabled().performClick()

        assertEquals("a".repeat(32), saved)
    }

    @Test
    fun going_back_to_muvisss_key_is_offered_only_while_a_key_is_in_use() = runComposeUiTest {
        row(TmdbKeyUiState(visible = true, source = TmdbDataSource.MUVISS))
        onNodeWithTag(TMDB_KEY_ROW_TAG).performClick()
        onNodeWithTag(TMDB_KEY_USE_MUVISS_TAG).assertDoesNotExist()
    }

    @Test
    fun use_muvisss_key_calls_back_when_a_key_is_in_use() = runComposeUiTest {
        var used = false
        row(TmdbKeyUiState(visible = true, source = TmdbDataSource.YOURS), onUseMuviss = { used = true })
        onNodeWithTag(TMDB_KEY_ROW_TAG).performClick()
        onNodeWithTag(TMDB_KEY_USE_MUVISS_TAG).performClick()
        assertEquals(true, used)
    }

    @Test
    fun a_rejected_key_says_so_under_the_field() = runComposeUiTest {
        row(TmdbKeyUiState(visible = true, problem = TmdbKeyProblem.REJECTED))
        onNodeWithTag(TMDB_KEY_ROW_TAG).performClick()
        onNodeWithText("TMDB rejected that key. Check it and try again.").assertExists()
    }

    @Test
    fun nothing_can_be_pressed_while_a_key_is_being_checked() = runComposeUiTest {
        row(TmdbKeyUiState(visible = true, checking = true))
        onNodeWithTag(TMDB_KEY_ROW_TAG).performClick()
        onNodeWithTag(TMDB_KEY_SAVE_TAG).assertIsNotEnabled()
        onNodeWithText("Checking with TMDB…").assertExists()
    }
}
