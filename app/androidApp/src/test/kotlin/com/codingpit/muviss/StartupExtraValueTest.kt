package com.codingpit.muviss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StartupExtraValueTest {

    @Test
    fun `fresh launch with an extra takes the intent value`() {
        // Given a first creation whose launch intent carries a notification's media id
        val fromIntent = "603"

        // When the activity decides its starting value
        val value = startupExtraValue(isRecreated = false, fromIntent = fromIntent, savedPending = null)

        // Then the intent value is used
        assertEquals("603", value)
    }

    @Test
    fun `fresh launch without an extra starts empty`() {
        // Given a first creation with no extra on the launch intent
        // When the activity decides its starting value
        val value = startupExtraValue(isRecreated = false, fromIntent = null, savedPending = null)

        // Then there is nothing to consume
        assertNull(value)
    }

    @Test
    fun `recreation after rotation keeps a value that was still pending`() {
        // Given the activity is recreated and the saved state still holds an unconsumed value
        // When the activity decides its starting value
        val value = startupExtraValue(isRecreated = true, fromIntent = "603", savedPending = "603")

        // Then the pending value is restored, once
        assertEquals("603", value)
    }

    @Test
    fun `recreation after the value was consumed does not re-arm the launch intent extra`() {
        // Given the activity was recreated after the consumer cleared the value, while the
        // launch intent still carries the extra (the #805 bug)
        // When the activity decides its starting value
        val value = startupExtraValue(isRecreated = true, fromIntent = "603", savedPending = null)

        // Then the already-consumed extra is not delivered again
        assertNull(value)
    }
}
