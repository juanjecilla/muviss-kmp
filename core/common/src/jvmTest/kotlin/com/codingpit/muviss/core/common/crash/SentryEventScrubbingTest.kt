package com.codingpit.muviss.core.common.crash

import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import io.sentry.kotlin.multiplatform.protocol.Message
import io.sentry.kotlin.multiplatform.protocol.SentryException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The JVM actual's `beforeSend` mapping, against the real SDK types. The Android
 * and iOS actuals carry the same code over the same `io.sentry.kotlin.multiplatform`
 * types; they are compile-checked, and this is the only place it *runs* — a
 * shared source set for the three would need a hierarchy the default template
 * does not give us.
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
        }

        val scrubbed = event.scrubbed()

        assertFalse(scrubbed.message!!.message!!.contains(key))
        assertFalse(scrubbed.message!!.formatted!!.contains(key))
        assertFalse(scrubbed.exceptions.single().value!!.contains(key))
        assertFalse(scrubbed.breadcrumbs.single().message!!.contains(key))
        assertFalse(scrubbed.breadcrumbs.single().getData()!!["url"].toString().contains(key))
        assertEquals("IOException", scrubbed.exceptions.single().type)
    }

    @Test
    fun an_event_with_no_message_or_breadcrumbs_survives() {
        val scrubbed = SentryEvent().scrubbed()

        assertEquals(null, scrubbed.message)
    }
}
