package com.codingpit.muviss.core.sync

/**
 * Runs work with no sync cycle in flight and none starting until it returns. Delete all data
 * needs it (#645): signing out stops the *next* cycle, but one already running could apply a
 * pulled page after the erase and bring rows back. [SyncEngine] implements it with the same
 * mutex that serialises its cycles.
 */
interface SyncIdleLock {
    suspend fun runIdle(block: suspend () -> Unit)

    /** For graphs and tests with no engine: nothing can be in flight. */
    object None : SyncIdleLock {
        override suspend fun runIdle(block: suspend () -> Unit) = block()
    }
}
