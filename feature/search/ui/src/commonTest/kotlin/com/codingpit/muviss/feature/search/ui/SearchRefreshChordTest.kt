package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #965: Ctrl or Cmd with R, on the key going down, is the refresh chord. Nothing else is. */
class SearchRefreshChordTest {

    @Test
    fun ctrl_r_on_key_down_refreshes() {
        assertTrue(isRefreshChord(KeyEventType.KeyDown, Key.R, ctrl = true, meta = false))
    }

    @Test
    fun cmd_r_on_key_down_refreshes() {
        assertTrue(isRefreshChord(KeyEventType.KeyDown, Key.R, ctrl = false, meta = true))
    }

    @Test
    fun a_bare_r_is_typing_and_does_not_refresh() {
        assertFalse(isRefreshChord(KeyEventType.KeyDown, Key.R, ctrl = false, meta = false))
    }

    @Test
    fun the_key_coming_up_does_not_refresh_again() {
        assertFalse(isRefreshChord(KeyEventType.KeyUp, Key.R, ctrl = true, meta = false))
    }

    @Test
    fun another_letter_with_ctrl_does_not_refresh() {
        assertFalse(isRefreshChord(KeyEventType.KeyDown, Key.F, ctrl = true, meta = false))
    }
}
