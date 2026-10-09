package com.codingpit.muviss.core.common.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// The JVM has no portable connectivity signal; a desktop Retry stays manual.
actual class DefaultConnectivityMonitor actual constructor() : ConnectivityMonitor {
    actual override val isOnline: Flow<Boolean> = flowOf(true)
}
