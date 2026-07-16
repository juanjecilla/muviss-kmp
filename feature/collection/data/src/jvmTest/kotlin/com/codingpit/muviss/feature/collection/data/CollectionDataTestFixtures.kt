package com.codingpit.muviss.feature.collection.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import kotlinx.coroutines.CoroutineDispatcher

/** Shared by this module's SQLDelight repository tests (previously duplicated per-file). */
internal class ImmediateDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

internal class FakeClock(private var millis: Long) : AppClock {
    override fun nowEpochMs(): Long = millis
    fun advanceTo(newMillis: Long) {
        millis = newMillis
    }
}
