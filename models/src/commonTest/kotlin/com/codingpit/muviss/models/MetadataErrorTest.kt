package com.codingpit.muviss.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
