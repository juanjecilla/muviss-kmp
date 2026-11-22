@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Delete all data runs inside [SyncIdleLock.runIdle] (#645). Signing out only stops the next
 * cycle; one already pulling could apply a page after the erase, so the erase must wait for it.
 */
class SyncIdleLockTest {

    /** A backend whose pull holds until [release] completes, standing in for a slow page. */
    private class HeldPullBackend(
        private val inner: FakeSyncBackend,
        val release: CompletableDeferred<Unit>,
        private val onPulled: () -> Unit,
    ) : SyncBackend by inner {
        override suspend fun pull(after: Map<SyncTable, SyncCursor>, onPage: suspend (SyncPage) -> Unit): Result<Unit> {
            release.await()
            return inner.pull(after, onPage).also { onPulled() }
        }
    }

    @Test
    fun run_idle_waits_for_the_cycle_in_flight_before_it_starts() = runTest {
        val events = mutableListOf<String>()
        val backend = HeldPullBackend(FakeSyncBackend(), CompletableDeferred()) { events += "pulled" }
        val engine = SyncEngine(backend, inMemoryDatabase(), ImmediateDispatchers(UnconfinedTestDispatcher(testScheduler)), FakeClock(1_000L))

        val cycle = launch { engine.syncNow(SyncTrigger.Manual) }
        val idle = launch { engine.runIdle { events += "erase" } }
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList(), events, "the erase waits while a pull is still in flight")

        backend.release.complete(Unit)
        cycle.join()
        idle.join()

        assertEquals(listOf("pulled", "erase"), events)
    }

    @Test
    fun no_cycle_starts_while_run_idle_holds_the_lock() = runTest {
        val engine = SyncEngine(FakeSyncBackend(), inMemoryDatabase(), ImmediateDispatchers(UnconfinedTestDispatcher(testScheduler)), FakeClock(1_000L))
        val events = mutableListOf<String>()
        val hold = CompletableDeferred<Unit>()

        val idle = launch {
            engine.runIdle {
                events += "erase started"
                hold.await()
                events += "erase done"
            }
        }
        testScheduler.advanceUntilIdle()
        val cycle = launch {
            engine.syncNow(SyncTrigger.Manual)
            events += "cycle done"
        }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("erase started"), events, "a sync asked for mid-erase waits")

        hold.complete(Unit)
        idle.join()
        cycle.join()

        assertEquals(listOf("erase started", "erase done", "cycle done"), events)
    }
}
