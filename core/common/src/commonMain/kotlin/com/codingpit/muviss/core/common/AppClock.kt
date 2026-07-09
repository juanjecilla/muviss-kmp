package com.codingpit.muviss.core.common

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Wall-clock time, abstracted so it can be faked in tests (e.g. asserting
 * `addedAtEpochMs`/`updatedAtEpochMs` on a saved [collection entry]
 * [com.codingpit.muviss.feature.collection.domain.CollectionEntry]).
 */
interface AppClock {
    fun nowEpochMs(): Long
}

@OptIn(ExperimentalTime::class)
class SystemClock : AppClock {
    override fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()
}
