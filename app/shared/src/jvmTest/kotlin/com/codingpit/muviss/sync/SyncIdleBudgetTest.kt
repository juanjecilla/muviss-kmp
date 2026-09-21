@file:OptIn(ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.sync

import com.codingpit.muviss.core.sync.SyncOutcome
import com.codingpit.muviss.core.sync.SyncTable
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val CLOCK_BASE = 1_800_000_000_000L

/**
 * What an idle device costs (EPIC 40): a Library visit and a foreground with
 * nothing dirty must not write user data and must not ask the server for more
 * than one page per table. Automatic sync runs far more often than a person
 * taps a button, so an idle cycle that quietly re-stamped rows would turn every
 * app start into an outbound push and, worse, a conflict with the other
 * device's real edits (EPIC 39's snapshot-refresh defect).
 *
 * Counts, not timings: a count is the same on every machine.
 */
class SyncIdleBudgetTest {

    private val apps = mutableListOf<SyncApp>()

    @AfterTest
    fun tearDown() = apps.forEach { it.close() }

    private suspend fun TestScope.settledApp(server: FakeSupabaseServer): SyncApp {
        val app = SyncApp(server, UnconfinedTestDispatcher(testScheduler), LambdaClock { CLOCK_BASE + testScheduler.currentTime }).also { apps += it }
        app.signIn()
        app.flags.setSyncAutomatically(true)
        app.addMovie()
        app.addMovie(id = "27205", title = "Inception")
        // One full cycle first: after it the device is genuinely clean.
        assertTrue(app.engine.syncNow() is SyncOutcome.Success)
        return app
    }

    private suspend fun TestScope.settle(maxWaitMs: Long = 3_000, until: () -> Boolean) {
        var waited = 0L
        while (!until() && waited < maxWaitMs) {
            runCurrent()
            // Blocking on purpose: suspending here would let runTest auto-advance virtual time.
            Thread.sleep(10)
            waited += 10
        }
        runCurrent()
    }

    private fun userData(app: SyncApp) = app.database.collectionEntryQueries.selectAll().executeAsList()

    @Test
    fun a_library_visit_with_nothing_dirty_issues_no_writes_at_all() = runTest {
        val app = settledApp(FakeSupabaseServer())
        app.driver.reset()

        // What the Library screen does on entry: read the list, read one title's membership.
        val summaries = app.collection.observeSummaries().first()
        app.collection.observeMembership(summaries.first().mediaId).first()

        assertEquals(0, app.driver.statements, "a visit writes nothing")
        assertEquals(0, app.driver.transactions)
        assertEquals(0L, app.engine.observePendingChanges().first(), "and leaves nothing waiting to be pushed")
    }

    @Test
    fun a_foreground_with_nothing_dirty_pushes_nothing_and_rewrites_no_user_data() = runTest {
        val server = FakeSupabaseServer()
        val app = settledApp(server)
        val before = userData(app)
        server.clearRecordedRequests()
        advanceTimeBy(90.seconds) // outside the 60 s skip window

        app.coordinator.onForeground()
        settle { server.requests.any { it.isSelect } }
        settle(maxWaitMs = 300) { false }

        assertEquals(0, server.requests.count { it.isUpsert }, "nothing dirty means no push")
        assertEquals(before, userData(app), "user rows are byte-for-byte what they were: not re-stamped, not re-dirtied")
        assertEquals(0L, app.engine.observePendingChanges().first())
    }

    @Test
    fun a_foreground_asks_for_at_most_one_page_per_table() = runTest {
        val server = FakeSupabaseServer()
        val app = settledApp(server)
        server.clearRecordedRequests()
        advanceTimeBy(90.seconds)

        app.coordinator.onForeground()
        settle { server.requests.size >= SyncTable.entries.size }
        settle(maxWaitMs = 300) { false }

        val perTable = server.requests.filter { it.isSelect }.groupingBy { it.table }.eachCount()
        assertEquals(SyncTable.entries.size, perTable.size, "every table is asked about: $perTable")
        perTable.forEach { (table, count) -> assertEquals(1, count, "one pull request for $table") }
    }

    @Test
    fun a_foreground_inside_the_skip_window_asks_the_server_nothing() = runTest {
        val server = FakeSupabaseServer()
        val app = settledApp(server) // just finished a cycle
        server.clearRecordedRequests()
        advanceTimeBy(10.seconds)

        app.coordinator.onForeground()
        settle(maxWaitMs = 300) { false }

        assertEquals(emptyList(), server.requests)
    }
}
