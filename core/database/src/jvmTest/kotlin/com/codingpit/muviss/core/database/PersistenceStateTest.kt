package com.codingpit.muviss.core.database

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The web driver's own storage state is only reachable through a live Web
 * Worker, which `jvmTest` has no browser to give it — so the decision is pulled
 * out as a pure function and tested here, the same shape as `schemaStepFor`.
 */
class PersistenceStateTest {

    @Test
    fun `the tab holding the lock is durable`() {
        assertEquals(
            PersistenceState.Durable,
            persistenceStateOf(writer = true, webLocksSupported = true),
        )
    }

    @Test
    fun `a tab that lost the election is read-only`() {
        assertEquals(
            PersistenceState.ReadOnlyTab,
            persistenceStateOf(writer = false, webLocksSupported = true),
        )
    }

    /**
     * Without Web Locks nobody is elected, so `writer` is false for a reason
     * that has nothing to do with another tab. Reporting "open in another tab"
     * here would be a lie — there may be no other tab.
     */
    @Test
    fun `no Web Locks means nothing persists anywhere, not that another tab has it`() {
        assertEquals(
            PersistenceState.NotPersisted,
            persistenceStateOf(writer = false, webLocksSupported = false),
        )
    }

    /**
     * Cannot happen from the worker, which only sets `writer` after the lock is
     * granted — but the two booleans arrive over a `postMessage` boundary, and
     * the order of the `when` is what decides this case. Pinned so a later
     * reordering has to be deliberate.
     */
    @Test
    fun `support is checked before the writer flag`() {
        assertEquals(
            PersistenceState.NotPersisted,
            persistenceStateOf(writer = true, webLocksSupported = false),
        )
    }

    /** A writer whose last IndexedDB put failed is the one case where "Durable" would be a lie (#292). */
    @Test
    fun `a writer whose last snapshot failed is not durable`() {
        assertEquals(
            PersistenceState.WriteFailed,
            persistenceStateOf(writer = true, webLocksSupported = true, lastWriteFailed = true),
        )
    }

    @Test
    fun `a later good write clears the failure`() {
        assertEquals(
            PersistenceState.Durable,
            persistenceStateOf(writer = true, webLocksSupported = true, lastWriteFailed = false),
        )
    }

    /** A read-only tab never writes, so a failure flag it cannot have set must not hide the real reason. */
    @Test
    fun `a failure flag does not turn a read-only tab into a failed writer`() {
        assertEquals(
            PersistenceState.ReadOnlyTab,
            persistenceStateOf(writer = false, webLocksSupported = true, lastWriteFailed = true),
        )
        assertEquals(
            PersistenceState.NotPersisted,
            persistenceStateOf(writer = true, webLocksSupported = false, lastWriteFailed = true),
        )
    }
}
