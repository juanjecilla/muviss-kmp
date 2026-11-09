package com.codingpit.muviss.feature.nextpick.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CrashPickRankerReporterTest {

    private val sent = mutableListOf<Throwable>()
    private val reporter = CrashPickRankerReporter { sent += it }

    @Test
    fun a_failure_is_sent_with_the_class_name_as_its_message_and_no_cause() {
        reporter.failed("IllegalStateException")

        val event = assertIs<PickRankerFailed>(sent.single())
        assertEquals("IllegalStateException", event.message)
        assertNull(event.cause)
    }

    @Test
    fun a_timeout_is_sent_as_its_own_event_and_never_as_a_failure() {
        reporter.timedOut()

        val event = sent.single()
        assertIs<PickRankerTimedOut>(event)
        assertNull(event.cause)
    }
}
