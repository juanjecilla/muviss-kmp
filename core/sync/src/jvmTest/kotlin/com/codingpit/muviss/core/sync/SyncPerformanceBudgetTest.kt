package com.codingpit.muviss.core.sync

import app.cash.sqldelight.Query
import com.codingpit.muviss.core.testing.CountingDriver
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Operation-count budgets for sync (defect 11), asserted with [CountingDriver]
 * rather than a stopwatch: a count is stable on any machine, and a regression
 * that turns a page into a row-at-a-time write shows up as a number.
 *
 * What the budgets protect is not speed for its own sake. Every committed
 * write re-runs every `Flow` watching the table, so a 3,000-row pull that
 * commits 6,000 times re-renders the Library 6,000 times — and each of those
 * intermediate states is one a screen can see and a crash can happen in.
 */
class SyncPerformanceBudgetTest {

    private val pageSize = 500

    private fun progressRow(episode: Int) = JsonObject(
        mapOf(
            "episode_id" to JsonPrimitive("tmdb:tv:1/1/$episode"),
            "media_id" to JsonPrimitive("tmdb:tv:1"),
            "season_number" to JsonPrimitive(1),
            "episode_number" to JsonPrimitive(episode),
            "seen" to JsonPrimitive(true),
            "updated_at_epoch_ms" to JsonPrimitive(1_000L + episode),
        ),
    )

    @Test
    fun a_3000_row_pull_commits_at_most_once_per_page_and_notifies_once_per_page() = runTest {
        val server = FakeSupabaseServer(maxRows = Int.MAX_VALUE)
        server.signUp("alice")
        (1..3_000).forEach { server.seed("episode_progress", "alice", progressRow(it)) }
        lateinit var counting: CountingDriver
        val device = TestDevice(server, wrapDriver = { CountingDriver(it).also { c -> counting = c } })
        // A Flow over this query re-emits once per change notification, so
        // counting notifications is counting emissions — without the
        // scheduling luck a real collector would add to the number.
        val notifications = AtomicInteger()
        val listener = Query.Listener { notifications.incrementAndGet() }
        val watched = device.database.episodeProgressQueries.selectAll()
        watched.addListener(listener)
        counting.reset()

        device.sync()
        watched.removeListener(listener)

        val pages = 3_000 / pageSize
        assertEquals(3_000, device.allProgress().size)
        // One write per row plus a fixed handful (owner, cursors, reconcile, the
        // aired floor per page). A per-row *read* is a query, counted apart.
        assertTrue(counting.statements <= 3_000 + 100, "${counting.statements} write statements for 3,000 rows")
        assertTrue(counting.transactions <= pages + 2, "${counting.transactions} transactions for $pages pages")
        assertTrue(notifications.get() <= pages + 2, "${notifications.get()} change notifications for $pages pages: every watching Flow re-read the table per row")
    }

    @Test
    fun a_push_makes_ceil_rows_over_500_requests_per_table() = runTest {
        val server = FakeSupabaseServer()
        val device = TestDevice(server)
        repeat(1_250) { device.tick("tmdb:tv:1", episode = it + 1, updatedAt = 1_000L + it) }
        repeat(501) { device.saveEntry("tmdb:movie:$it", updatedAt = 1_000L + it) }

        device.sync()

        assertEquals(3, server.requestsTo("episode_progress", method = "POST").size)
        assertEquals(2, server.requestsTo("collection_entry", method = "POST").size)
        assertEquals(0, server.requestsTo("episode_play", method = "POST").size, "a table with nothing dirty is not posted at all")
    }
}
