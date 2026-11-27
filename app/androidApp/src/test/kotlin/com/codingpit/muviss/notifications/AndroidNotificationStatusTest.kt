package com.codingpit.muviss.notifications

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidNotificationStatusTest {

    @Test
    fun `the grant is read once and canAskAgain is given that same reading`() {
        // The grant flips to "granted" after the first read, as it would if the person allowed it in the middle of a call.
        val answers = ArrayDeque(listOf(false, true))
        var reads = 0
        var askedWith: Boolean? = null

        val status = observeNotificationStatus(
            granted = {
                reads++
                answers.removeFirst()
            },
            canAskAgain = { granted ->
                askedWith = granted
                false
            },
        )

        assertEquals(1, reads, "the grant must be read once per status")
        assertEquals(false, askedWith, "canAskAgain must receive the reading the status was built from")
        assertEquals(true, status.blocked)
        assertEquals(false, status.canAskAgain)
    }

    @Test
    fun `a granted permission is not blocked and passes granted through`() {
        var askedWith: Boolean? = null

        val status = observeNotificationStatus(granted = { true }, canAskAgain = { granted ->
            askedWith = granted
            true
        })

        assertEquals(false, status.blocked)
        assertEquals(true, askedWith)
        assertEquals(true, status.canAskAgain)
    }
}
