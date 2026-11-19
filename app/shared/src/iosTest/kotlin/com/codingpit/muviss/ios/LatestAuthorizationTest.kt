package com.codingpit.muviss.ios

import kotlin.test.Test
import kotlin.test.assertEquals

// #846: an iOS answer to an older read must not overwrite a newer one, whatever order they arrive in.
class LatestAuthorizationTest {

    @Test
    fun a_late_answer_to_an_older_read_does_not_overwrite_a_newer_answer() {
        val cache = LatestAuthorization(initial = 0L)
        val older = cache.nextRead()
        val newer = cache.nextRead()

        cache.publish(newer, status = 1L)
        cache.publish(older, status = 2L)

        assertEquals(1L, cache.status)
    }

    @Test
    fun a_newer_answer_replaces_an_older_one_that_arrived_first() {
        val cache = LatestAuthorization(initial = 0L)
        val older = cache.nextRead()
        val newer = cache.nextRead()

        cache.publish(older, status = 2L)
        cache.publish(newer, status = 1L)

        assertEquals(1L, cache.status)
    }

    @Test
    fun the_initial_status_holds_until_the_first_answer() {
        assertEquals(0L, LatestAuthorization(initial = 0L).status)
    }
}
