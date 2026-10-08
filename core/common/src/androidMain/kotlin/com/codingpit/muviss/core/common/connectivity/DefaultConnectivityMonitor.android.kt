package com.codingpit.muviss.core.common.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// MuvissApplication binds AndroidConnectivityMonitor, which needs a Context.
actual class DefaultConnectivityMonitor actual constructor() : ConnectivityMonitor {
    actual override val isOnline: Flow<Boolean> = flowOf(true)
}
