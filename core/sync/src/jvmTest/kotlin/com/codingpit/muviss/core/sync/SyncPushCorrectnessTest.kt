package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.testing.FakeSupabaseServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Defects 3, 4 and the chunking half of 12: what a push does to the server, and to the local dirty flag. */
class SyncPushCorrectnessTest {

    @Test
    fun clearing_every_nullable_column_reaches_the_server() = runTest {
        val server = FakeSupabaseServer()
        val device = TestDevice(server)
        device.saveEntry(
            "tmdb:movie:1",
            updatedAt = 1_000L,
            rating = 8,
            note = "loved it",
            posterUrl = "https://image.test/p.jpg",
            releaseYear = 1999,
            runtimeMinutes = 136,
        )
        device.sync()

        // The user clears all five; the row is re-saved locally with nulls.
        device.saveEntry("tmdb:movie:1", updatedAt = 2_000L)
        device.sync()

        val stored = server.row("collection_entry", "alice", "tmdb:movie:1")!!
        listOf("rating", "note", "poster_url", "release_year", "runtime_minutes").forEach { column ->
            assertEquals(JsonNull, stored.getValue(column), "$column was cleared locally but the server still holds a value")
        }
        assertEquals(2_000L, stored.getValue("updated_at_epoch_ms").jsonPrimitive.long)
    }

    @Test
    fun a_cleared_rating_does_not_come_back_on_another_device() = runTest {
        val server = FakeSupabaseServer()
        val a = TestDevice(server)
        val b = TestDevice(server)
        a.saveEntry("tmdb:movie:1", updatedAt = 1_000L, rating = 9)
        a.sync()
        b.sync()
        assertEquals(9L, b.entry("tmdb:movie:1")!!.rating)

        a.database.collectionEntryQueries.setRating(rating = null, now = 2_000L, mediaId = "tmdb:movie:1")
        a.sync()
        b.sync()

        assertNull(b.entry("tmdb:movie:1")!!.rating, "the cleared rating reappeared from the server")
    }

    @Test
    fun a_push_is_split_into_requests_of_at_most_500_rows_per_table() = runTest {
        val server = FakeSupabaseServer()
        val device = TestDevice(server)
        repeat(1_200) { device.tick("tmdb:tv:1", episode = it + 1, updatedAt = 1_000L + it) }

        device.sync()

        val posts = server.requestsTo("episode_progress", method = "POST")
        assertEquals(3, posts.size, "1,200 rows is ceil(1200 / 500) requests")
        assertTrue(posts.all { it.rows.size <= 500 })
        assertEquals(1_200, server.rows("episode_progress", "alice").size)
    }

    @Test
    fun an_edit_made_while_a_push_is_in_flight_stays_dirty() = runTest {
        val server = FakeSupabaseServer()
        val device = TestDevice(server)
        device.saveEntry("tmdb:movie:1", updatedAt = 1_000L, favorite = false)
        // The user taps the heart while the request is on the wire.
        var edited = false
        server.onRequest = { request ->
            if (request.isUpsert && request.table == "collection_entry" && !edited) {
                edited = true
                device.database.collectionEntryQueries.setFavorite(favorite = true, now = 2_000L, mediaId = "tmdb:movie:1")
            }
        }

        device.sync()

        val local = device.entry("tmdb:movie:1")!!
        assertTrue(local.favorite)
        assertTrue(local.isDirty, "the push carried the old row; clearing the flag by key alone marks the new edit as sent")
    }

    @Test
    fun an_edit_that_landed_mid_push_is_sent_by_the_next_sync() = runTest {
        val server = FakeSupabaseServer()
        val device = TestDevice(server)
        device.saveEntry("tmdb:movie:1", updatedAt = 1_000L, favorite = false)
        var edited = false
        server.onRequest = { request ->
            if (request.isUpsert && request.table == "collection_entry" && !edited) {
                edited = true
                device.database.collectionEntryQueries.setFavorite(favorite = true, now = 2_000L, mediaId = "tmdb:movie:1")
            }
        }

        device.sync()
        device.sync()

        assertEquals("true", server.row("collection_entry", "alice", "tmdb:movie:1")!!.getValue("favorite").jsonPrimitive.content)
    }
}
