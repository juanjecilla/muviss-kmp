package com.codingpit.muviss.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetadataErrorTest {

    @Test
    fun a_metadata_error_maps_to_its_own_copy() {
        assertEquals(MetadataError.Offline().userMessage, MetadataError.Offline().toUserMessage("fallback"))
        assertEquals(MetadataError.RateLimited(5).userMessage, MetadataError.RateLimited(5).toUserMessage("fallback"))
    }

    @Test
    fun any_other_exception_maps_to_the_fallback_and_its_message_is_discarded() {
        val leaky = IllegalStateException("Timeout for https://api.themoviedb.org/3/x?api_key=SECRET")

        val text = leaky.toUserMessage("Something went wrong")

        assertEquals("Something went wrong", text)
        assertFalse(text.contains("SECRET"))
    }

    @Test
    fun each_case_has_distinct_copy() {
        val copy = listOf(
            MetadataError.RateLimited(),
            MetadataError.Unauthorized(),
            MetadataError.NotFound(),
            MetadataError.Offline(),
            MetadataError.Unknown(),
        ).map { it.userMessage }

        assertEquals(copy.size, copy.toSet().size)
    }

    @Test
    fun an_unknown_with_a_status_names_it_in_the_message_but_not_in_the_copy() {
        assertEquals("Metadata request failed (HTTP 422)", MetadataError.Unknown(422).message)
        assertEquals("Metadata request failed", MetadataError.Unknown().message)
        assertEquals(MetadataError.Unknown().userMessage, MetadataError.Unknown(422).userMessage)
    }

    @Test
    fun routine_failures_are_the_ones_that_say_nothing_about_a_defect() {
        assertTrue(MetadataError.Offline().isRoutineFailure())
        assertTrue(MetadataError.RateLimited(5).isRoutineFailure())
        assertTrue(MetadataError.OwnKeyRejected().isRoutineFailure())
        assertTrue(MetadataError.Unknown(503).isRoutineFailure())
    }

    @Test
    fun a_request_we_built_wrong_or_could_not_read_is_still_reported() {
        assertFalse(MetadataError.Unknown(422).isRoutineFailure())
        assertFalse(MetadataError.Unknown().isRoutineFailure())
        assertFalse(MetadataError.Unauthorized().isRoutineFailure())
        assertFalse(MetadataError.NotFound().isRoutineFailure())
        assertFalse(IllegalStateException("bug").isRoutineFailure())
    }
}
