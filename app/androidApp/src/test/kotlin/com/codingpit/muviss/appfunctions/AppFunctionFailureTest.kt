package com.codingpit.muviss.appfunctions

import com.codingpit.muviss.models.MetadataError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppFunctionFailureTest {

    @Test
    fun every_metadata_error_maps_to_fixed_text_that_never_carries_a_message() {
        val leaky = "GET https://api.themoviedb.org/3/search/multi?api_key=SECRET-KEY-123 failed"
        // Ktor's exceptions carry the request URL in their message; the mapping must not read it.
        val failures = listOf(
            MetadataError.RateLimited(60),
            MetadataError.Unauthorized(),
            MetadataError.OwnKeyRejected(),
            MetadataError.NotFound(),
            MetadataError.Offline(),
            MetadataError.Unknown(502),
            MetadataError.Unknown(),
        )
        failures.forEach { error ->
            val text = error.appFunctionFailure().text
            assertFalse(text.contains("SECRET-KEY-123"), "text must not carry a key: $text")
            assertFalse(text.contains("api.themoviedb"), "text must not carry a URL: $text")
            assertFalse(text.contains(leaky))
        }
    }

    @Test
    fun the_failure_kind_follows_the_cause() {
        assertEquals(FailureKind.LIMIT_EXCEEDED, MetadataError.RateLimited().appFunctionFailure().kind)
        assertEquals(FailureKind.NOT_FOUND, MetadataError.NotFound().appFunctionFailure().kind)
        assertEquals(FailureKind.APP_UNKNOWN, MetadataError.Offline().appFunctionFailure().kind)
        assertEquals(FailureKind.APP_UNKNOWN, MetadataError.Unknown(500).appFunctionFailure().kind)
    }
}
