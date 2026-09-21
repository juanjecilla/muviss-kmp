package com.codingpit.muviss.core.common.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CrashScrubberTest {

    private val tmdbKey = "0123456789abcdef0123456789abcdef"

    @Test
    fun an_api_key_in_a_url_in_a_message_is_removed() {
        val message = "Request failed: GET https://api.themoviedb.org/3/search/multi?query=alien&api_key=$tmdbKey&language=en-US"

        val scrubbed = CrashScrubber.scrub(message)

        assertFalse(scrubbed.contains(tmdbKey), scrubbed)
        // The rest of the diagnosis survives: which call, which parameters.
        assertEquals(
            "Request failed: GET https://api.themoviedb.org/3/search/multi?query=alien&api_key=[redacted]&language=en-US",
            scrubbed,
        )
    }

    @Test
    fun a_key_at_the_end_of_the_url_is_removed() {
        assertEquals("https://x.test/a?api_key=[redacted]", CrashScrubber.scrub("https://x.test/a?api_key=$tmdbKey"))
    }

    @Test
    fun the_spellings_of_a_key_are_all_covered() {
        listOf("api_key", "apikey", "API-KEY", "access_token", "refresh_token", "id_token", "client_secret", "code_verifier", "password")
            .forEach { name ->
                val scrubbed = CrashScrubber.scrub("bad request ?$name=s3cr3t-value&x=1")
                assertFalse(scrubbed.contains("s3cr3t-value"), "$name: $scrubbed")
                assertEquals("bad request ?$name=[redacted]&x=1", scrubbed)
            }
    }

    @Test
    fun a_header_or_json_style_secret_is_removed() {
        assertEquals("apikey: [redacted]", CrashScrubber.scrub("apikey: eyJhbGciOi.payload.sig"))
        assertEquals("""{"access_token":"[redacted]"}""", CrashScrubber.scrub("""{"access_token":"abc123"}"""))
    }

    @Test
    fun a_bearer_credential_is_removed() {
        val scrubbed = CrashScrubber.scrub("401 for Authorization: Bearer eyJhbGciOi.payload.sig-_=")

        assertFalse(scrubbed.contains("eyJhbGciOi"), scrubbed)
        assertEquals("401 for Authorization: Bearer [redacted]", scrubbed)
    }

    @Test
    fun text_with_nothing_secret_in_it_is_returned_unchanged() {
        val message = "Unable to resolve host api.themoviedb.org: no address associated with hostname"

        assertEquals(message, CrashScrubber.scrub(message))
    }

    @Test
    fun null_stays_null() {
        assertEquals(null, CrashScrubber.scrubOrNull(null))
    }
}
