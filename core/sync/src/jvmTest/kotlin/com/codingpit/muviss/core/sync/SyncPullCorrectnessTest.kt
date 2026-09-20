package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.testing.FakeSupabaseServer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Defects 1 and 2 of EPIC 39: what a pull is allowed to miss.
 *
 * Every test here runs the real backend and engine against
 * [FakeSupabaseServer], because none of these can fail against an in-memory
 * fake: they are properties of a response that was truncated by the server, or
 * of two devices' clocks disagreeing, and neither exists until something
 * actually serialises the exchange.
 */
class SyncPullCorrectnessTest {

    @Test
    fun a_pull_larger_than_the_servers_row_cap_returns_every_row() = runTest {
        // Supabase cuts a response at `max_rows` with a 200 and no marker.
        val server = FakeSupabaseServer(maxRows = 300)
        val writer = TestDevice(server)
        val reader = TestDevice(server)
        repeat(1_000) { writer.tick("tmdb:tv:1", season = 1, episode = it + 1, updatedAt = 1_000L + it) }
        writer.sync()

        reader.sync()

        assertEquals(1_000, reader.allProgress().size, "rows past the server's cap were silently dropped and never asked for again")
    }

    @Test
    fun a_second_pull_after_a_capped_one_still_finds_the_rest() = runTest {
        val server = FakeSupabaseServer(maxRows = 300)
        val writer = TestDevice(server)
        val reader = TestDevice(server)
        repeat(1_000) { writer.tick("tmdb:tv:1", season = 1, episode = it + 1, updatedAt = 1_000L + it) }
        writer.sync()

        reader.sync()
        reader.sync()

        assertEquals(1_000, reader.allProgress().size)
    }

    @Test
    fun an_offline_edit_older_than_another_devices_newest_edit_is_still_pulled() = runTest {
        val server = FakeSupabaseServer()
        val a = TestDevice(server)
        val b = TestDevice(server)
        a.saveEntry("tmdb:movie:1", updatedAt = 1_000L)
        a.saveEntry("tmdb:movie:2", updatedAt = 1_000L)
        a.sync()
        b.sync()

        // B edits movie 1 while offline, at t=2000. A edits movie 2 later, at
        // t=5000, and syncs first — which is what moves A's pull cursor past
        // B's edit before B ever pushes it.
        b.database.collectionEntryQueries.setFavorite(favorite = true, now = 2_000L, mediaId = "tmdb:movie:1")
        a.database.collectionEntryQueries.setFavorite(favorite = true, now = 5_000L, mediaId = "tmdb:movie:2")
        a.sync()
        b.sync()
        a.sync()

        assertTrue(a.entry("tmdb:movie:1")!!.favorite, "an edit pushed late must still reach a device whose cursor has already moved on")
    }

    @Test
    fun rows_stamped_in_one_millisecond_and_pushed_in_separate_requests_all_arrive() = runTest {
        val server = FakeSupabaseServer()
        val writer = TestDevice(server)
        val reader = TestDevice(server)
        writer.saveEntry("tmdb:tv:1", updatedAt = 100L, mediaType = "TV")
        writer.tick("tmdb:tv:1", episode = 1, updatedAt = 100L)
        var readerSynced = false
        // Push sends one request per table. Let the reader pull between the
        // first and the second, exactly where a real network would.
        server.onRequest = { request ->
            if (request.isUpsert && request.table == "episode_progress" && !readerSynced) {
                readerSynced = true
                reader.sync()
            }
        }

        writer.sync()
        reader.sync()

        assertEquals(1, reader.allProgress().size, "a row stamped in the same millisecond as one already pulled is lost at the gt boundary")
    }

    @Test
    fun a_device_with_a_fast_clock_does_not_stop_later_honest_edits_reaching_it() = runTest {
        var serverNow = 10_000_000L
        val server = FakeSupabaseServer(serverClock = { serverNow })
        val fast = TestDevice(server, startMillis = serverNow + 86_400_000L)
        val honest = TestDevice(server, startMillis = serverNow)
        fast.saveEntry("tmdb:movie:1", updatedAt = fast.clock.nowEpochMs())
        fast.sync()
        honest.sync()

        serverNow += 120_000L
        honest.clock.advanceTo(serverNow)
        honest.database.collectionEntryQueries.setFavorite(favorite = true, now = serverNow, mediaId = "tmdb:movie:1")
        honest.sync()
        fast.sync()

        assertTrue(fast.entry("tmdb:movie:1")!!.favorite, "the fast device kept its own far-future stamp over the server's later, real edit")
    }
}
