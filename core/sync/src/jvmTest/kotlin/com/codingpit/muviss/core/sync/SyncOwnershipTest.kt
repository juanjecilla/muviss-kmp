package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.testing.FakeSupabaseServer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Defects 7 and 9: whose data a device is syncing, and what has never been sent. */
class SyncOwnershipTest {

    @Test
    fun rows_that_were_never_pushed_are_sent_on_the_first_sync_even_when_marked_clean() = runTest {
        // Rewatch history from before `7.sqm`: the migration carried `isDirty`
        // across unchanged, and those ticks had long since been pushed, so the
        // plays derived from them were clean and would never have been sent.
        val server = FakeSupabaseServer()
        val device = TestDevice(server)
        device.tick("tmdb:tv:1", episode = 1, updatedAt = 1_000L, isDirty = false)
        device.play("tmdb:tv:1", episode = 1, watchedAt = 1_000L, isDirty = false)
        device.play("tmdb:tv:1", episode = 1, watchedAt = 2_000L, isDirty = false)

        device.sync()

        assertEquals(2, server.rows("episode_play", "alice").size, "history that predates sync never reached the server")
        assertEquals(1, server.rows("episode_progress", "alice").size)
    }

    @Test
    fun one_accounts_unsent_rows_are_not_pushed_into_another_account() = runTest {
        val server = FakeSupabaseServer()
        val device = TestDevice(server)
        device.saveEntry("tmdb:movie:1")
        device.sync()
        // An edit made while signed in as alice and not yet sent.
        device.saveEntry("tmdb:movie:2")

        device.signInAs("bob")
        device.sync()

        assertTrue(
            server.rows("collection_entry", "bob").isEmpty(),
            "the second account received the first account's library",
        )
    }
}
