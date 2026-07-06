package com.codingpit.muviss.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Abstraction over coroutine dispatchers so use cases and repositories can be
 * driven with a test dispatcher. [io] is backed by a real IO pool on JVM/Android
 * and falls back to [Dispatchers.Default] where no IO pool exists.
 */
interface AppDispatchers {
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
}

class DefaultAppDispatchers : AppDispatchers {
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = platformIoDispatcher()
}

/** IO dispatcher where the platform provides one, otherwise [Dispatchers.Default]. */
expect fun platformIoDispatcher(): CoroutineDispatcher
