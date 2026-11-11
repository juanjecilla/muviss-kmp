package com.codingpit.muviss.sync

import com.codingpit.muviss.core.sync.SyncOutcome
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import com.codingpit.muviss.feature.settings.domain.LocalDataEraser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CLOCK_BASE = 1_800_000_000_000L

/**
 * Delete all data against the server, end to end (#677, follow-up of #645). Erasing signs the
 * device out and empties it; a sync after that must pull nothing, and signing back in must bring
 * the account's library back, through the same path a real sign-in takes.
 */
class DeleteAllDataSyncTest {

    private val apps = mutableListOf<SyncApp>()

    @AfterTest
    fun tearDown() = apps.forEach { it.close() }

    private fun TestScope.newApp(server: FakeSupabaseServer) = SyncApp(
        server = server,
        dispatcher = UnconfinedTestDispatcher(testScheduler),
        clock = LambdaClock { CLOCK_BASE + testScheduler.currentTime },
    ).also { apps += it }

    @Test
    fun erase_then_sync_pulls_nothing_and_signing_back_in_restores_the_library() = runTest {
        val server = FakeSupabaseServer()
        val device = newApp(server)
        device.signInWithOAuth()
        device.addMovie()
        assertTrue(device.engine.syncNow() is SyncOutcome.Success, "the library reaches the account first")
        assertEquals(1, server.rows("collection_entry", "alice").size)

        device.koin.get<LocalDataEraser>().deleteAllData()
        assertEquals(0, device.collection.observeSummaries().first().size, "the erase empties this device")

        server.clearRecordedRequests()
        assertEquals(SyncOutcome.NotSignedIn, device.engine.syncNow(), "signed out, the sync does not run")
        assertEquals(emptyList(), server.requests, "and it sends nothing to the server")
        assertEquals(0, device.collection.observeSummaries().first().size, "so nothing is pulled back")

        device.signInWithOAuth()
        assertTrue(device.engine.syncNow() is SyncOutcome.Success, "signing back in syncs again")
        assertEquals(1, device.collection.observeSummaries().first().size, "the account's library comes back")
    }
}
