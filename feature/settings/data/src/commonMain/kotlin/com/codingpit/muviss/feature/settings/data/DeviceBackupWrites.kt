package com.codingpit.muviss.feature.settings.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Orders Device Backup writes against Delete all data (ADR 0034), shared by
 * [SqlDelightSettingsRepository] and [SqlDelightLocalDataEraser] through Koin.
 *
 * A toggle's write cannot be cancelled once tapped (it runs `NonCancellable`, so
 * leaving the screen does not drop it), which means one tapped just before Delete
 * all data could otherwise run after it: its `ensureRow` would recreate the
 * settings row with the switch on, and the next open would apply it. So a write
 * remembers which erase it was asked before ([request]), and runs only if no
 * erase has happened since; both run under one lock, so neither interleaves.
 */
class DeviceBackupWrites {
    private val lock = Mutex()

    @Volatile
    private var erasures = 0L

    /** Called when the choice is made, before any dispatcher hop: the erase count it was made under. */
    fun request(): Long = erasures

    /** Runs [block] unless Delete all data has run since [requestedAt]; reports whether it ran. */
    suspend fun write(requestedAt: Long, block: suspend () -> Unit): Boolean = lock.withLock {
        if (requestedAt != erasures) return@withLock false
        block()
        true
    }

    suspend fun <T> erase(block: suspend () -> T): T = lock.withLock {
        erasures++
        block()
    }
}
