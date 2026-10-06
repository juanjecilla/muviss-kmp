package com.codingpit.muviss.core.common.crash

import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import io.sentry.kotlin.multiplatform.protocol.Message
import io.sentry.kotlin.multiplatform.protocol.SentryException
import io.sentry.kotlin.multiplatform.protocol.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * [SentryBackend.scrubbed] against the real SDK types, run on `jvmTest` (Compose
 * UI/Sentry native bits aside, that is where this repo's JVM-hosted tests run —
 * see CLAUDE.md). Since #109 this is no longer "the JVM copy, compile-checked
 * on the other two": `scrubbed()` lives once in `sentryMain`, compiled
 * unchanged into the android and iOS targets too, so this test exercises the
 * exact code those platforms ship, not a copy that could have drifted from it.
 */
class SentryEventScrubbingTest {

    private val key = "0123456789abcdef0123456789abcdef"
    private val url = "https://api.themoviedb.org/3/search/multi?query=alien&api_key=$key"

    @Test
    fun the_key_is_removed_from_every_field_that_can_carry_a_url() {
        val event = SentryEvent().apply {
            message = Message(message = "GET $url failed", formatted = "GET $url failed")
            exceptions = mutableListOf(SentryException(type = "IOException", value = "Unable to fetch $url"))
            breadcrumbs = mutableListOf(
                Breadcrumb(message = "request $url").apply { setData("url", url) },
            )
            tags = mutableMapOf("last_request" to url)
            contexts = mutableMapOf("request_url" to url, "screen_density" to 3)
        }

        val scrubbed = event.scrubbed()

        assertFalse(scrubbed.message!!.message!!.contains(key))
        assertFalse(scrubbed.message!!.formatted!!.contains(key))
        assertFalse(scrubbed.exceptions.single().value!!.contains(key))
        assertFalse(scrubbed.breadcrumbs.single().message!!.contains(key))
        assertFalse(scrubbed.breadcrumbs.single().getData()!!["url"].toString().contains(key))
        assertEquals("IOException", scrubbed.exceptions.single().type)
        assertFalse(scrubbed.tags["last_request"]!!.contains(key))
        assertFalse((scrubbed.contexts["request_url"] as String).contains(key))
        // A non-String context value is left alone rather than stringified.
        assertEquals(3, scrubbed.contexts["screen_density"])
    }

    @Test
    fun the_per_install_user_id_is_dropped() {
        val event = SentryEvent().apply { user = User().apply { id = "4b0fe4eae24b4661a569527d3586e0f8" } }

        assertNull(event.scrubbed().user)
    }

    @Test
    fun an_event_with_no_message_or_breadcrumbs_survives() {
        val scrubbed = SentryEvent().scrubbed()

        assertEquals(null, scrubbed.message)
    }
}
