@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The note used to be an always-open text field sitting between the rating and
 * the overview, which made an optional, usually-empty piece of personal data
 * the loudest thing on the detail screen. It is now one line until asked for.
 */
class NoteFieldTest {

    @Test
    fun with_no_note_it_offers_to_add_one() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                NoteField(note = null, onEdit = {})
            }
        }

        onNodeWithText(ADD_NOTE_LABEL).assertIsDisplayed()
    }

    @Test
    fun with_a_note_it_shows_the_note_instead_of_the_invitation() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                NoteField(note = "the finale earns it", onEdit = {})
            }
        }

        onNodeWithText("the finale earns it").assertIsDisplayed()
        onNodeWithText(ADD_NOTE_LABEL).assertDoesNotExist()
    }

    @Test
    fun tapping_it_asks_for_the_editor() = runComposeUiTest {
        var edited = false
        setContent {
            MuvissTheme(darkTheme = true) {
                NoteField(note = null, onEdit = { edited = true })
            }
        }

        onNodeWithTag(NOTE_FIELD_TAG).performClick()

        assertTrue(edited)
    }

    @Test
    fun the_editor_hands_back_what_was_typed() = runComposeUiTest {
        var saved: String? = null
        setContent {
            MuvissTheme(darkTheme = true) {
                NoteEditorContent(initial = "", onSave = { saved = it }, onCancel = {})
            }
        }

        onNodeWithTag(NOTE_EDITOR_TAG).performTextInput("worth a rewatch")
        onNodeWithText(SAVE_NOTE_LABEL).performClick()

        assertEquals("worth a rewatch", saved)
    }

    @Test
    fun the_editor_opens_on_the_existing_note_and_can_empty_it() = runComposeUiTest {
        var saved: String? = null
        setContent {
            MuvissTheme(darkTheme = true) {
                NoteEditorContent(initial = "old note", onSave = { saved = it }, onCancel = {})
            }
        }

        onNodeWithTag(NOTE_EDITOR_TAG).performTextClearance()
        onNodeWithText(SAVE_NOTE_LABEL).performClick()

        assertEquals("", saved, "clearing the field is how a note is deleted; collection's api normalizes blank to null")
    }

    @Test
    fun cancelling_saves_nothing() = runComposeUiTest {
        var saved: String? = null
        var cancelled = false
        setContent {
            MuvissTheme(darkTheme = true) {
                NoteEditorContent(initial = "old note", onSave = { saved = it }, onCancel = { cancelled = true })
            }
        }

        onNodeWithTag(NOTE_EDITOR_TAG).performTextInput("scratch that")
        onNodeWithText(CANCEL_NOTE_LABEL).performClick()

        assertTrue(cancelled)
        assertEquals(null, saved)
    }
}
