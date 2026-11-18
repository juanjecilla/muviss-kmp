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

    /**
     * An out-of-date tab (#61) gives up the lock, so `writer` is false, and it must
     * say so rather than read as an ordinary read-only tab: "open in another tab"
     * would send the user looking for a tab that may not be there.
     */
    @Test
    fun `a tab whose copy is out of date says so, and is not a read-only tab`() {
        assertEquals(
            PersistenceState.Stale,
            persistenceStateOf(writer = false, webLocksSupported = true, stale = true),
        )
    }

    /** Stale is only ever reported by a tab that could be asked to save. Without Web Locks nothing is elected, so support is still checked first. */
    @Test
    fun `no Web Locks outranks a stale copy`() {
        assertEquals(
            PersistenceState.NotPersisted,
            persistenceStateOf(writer = false, webLocksSupported = false, stale = true),
        )
    }

    @Test
    fun `a fresh copy is not stale`() {
        assertEquals(
            PersistenceState.ReadOnlyTab,
            persistenceStateOf(writer = false, webLocksSupported = true, stale = false),
        )
    }
}
