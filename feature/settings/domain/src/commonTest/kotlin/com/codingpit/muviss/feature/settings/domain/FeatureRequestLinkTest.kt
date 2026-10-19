package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.core.common.remoteconfig.FeatureRequestProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureRequestLinkTest {
    private val context = FeatureRequestContext(version = "1.2.0", platform = "android", os = "15", locale = "es-ES")

    @Test
    fun tally_carries_hidden_fields_in_the_query() {
        assertEquals(
            "https://tally.so/r/abc?version=1.2.0&platform=android&os=15&locale=es-ES",
            FeatureRequestLink.build(FeatureRequestProvider.Tally, "https://tally.so/r/abc", context),
        )
    }

    @Test
    fun tally_appends_to_an_existing_query_and_keeps_the_fragment_last() {
        assertEquals(
            "https://tally.so/r/abc?x=1&version=1.2.0&platform=android&os=15&locale=es-ES#top",
            FeatureRequestLink.build(FeatureRequestProvider.Tally, "https://tally.so/r/abc?x=1#top", context),
        )
    }

    @Test
    fun typeform_carries_hidden_fields_in_the_fragment() {
        assertEquals(
            "https://x.typeform.com/to/abc#version=1.2.0&platform=android&os=15&locale=es-ES",
            FeatureRequestLink.build(FeatureRequestProvider.Typeform, "https://x.typeform.com/to/abc", context),
        )
    }

    @Test
    fun typeform_merges_with_an_existing_fragment() {
        assertEquals(
            "https://x.typeform.com/to/abc#a=b&version=1.2.0&platform=android&os=15&locale=es-ES",
            FeatureRequestLink.build(FeatureRequestProvider.Typeform, "https://x.typeform.com/to/abc#a=b", context),
        )
    }

    @Test
    fun spaces_plus_and_non_ascii_are_percent_encoded() {
        val link = FeatureRequestLink.build(
            FeatureRequestProvider.Tally,
            "https://tally.so/r/abc",
            FeatureRequestContext(version = "1.0+beta 1", platform = "desktop", os = "Mac OS X 15.1", locale = "ñ"),
        )
        assertEquals(
            "https://tally.so/r/abc?version=1.0%2Bbeta%201&platform=desktop&os=Mac%20OS%20X%2015.1&locale=%C3%B1",
            link,
        )
    }

    @Test
    fun an_empty_field_is_left_out() {
        assertEquals(
            "https://tally.so/r/abc?version=1.2.0&platform=web&locale=en-US",
            FeatureRequestLink.build(
                FeatureRequestProvider.Tally,
                "https://tally.so/r/abc",
                FeatureRequestContext(version = "1.2.0", platform = "web", os = "", locale = "en-US"),
            ),
        )
    }

    @Test
    fun off_hides_the_row() {
        assertNull(FeatureRequestLink.build(FeatureRequestProvider.Off, "https://tally.so/r/abc", context))
    }

    @Test
    fun a_blank_or_missing_url_hides_the_row() {
        assertNull(FeatureRequestLink.build(FeatureRequestProvider.Tally, "  ", context))
        assertNull(FeatureRequestLink.build(FeatureRequestProvider.Typeform, null, context))
    }

    @Test
    fun an_unknown_stored_provider_reads_as_tally_not_off() {
        assertEquals(FeatureRequestProvider.Tally, FeatureRequestProvider.fromStored("jotform"))
        assertEquals(FeatureRequestProvider.Tally, FeatureRequestProvider.fromStored(null))
        assertEquals(FeatureRequestProvider.Off, FeatureRequestProvider.fromStored(" OFF "))
        assertEquals(FeatureRequestProvider.Typeform, FeatureRequestProvider.fromStored("typeform"))
    }
}
