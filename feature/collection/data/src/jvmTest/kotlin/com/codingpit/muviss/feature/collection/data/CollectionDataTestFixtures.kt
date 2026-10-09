package com.codingpit.muviss.feature.collection.data

import com.codingpit.muviss.core.common.AppDispatchers
import kotlinx.coroutines.CoroutineDispatcher

/** Shared by this module's SQLDelight repository tests (previously duplicated per-file). */
internal class ImmediateDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}
