package com.codingpit.muviss.core.common.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Whether the device has a usable network, as the platform reports it (#249).
 *
 * Nothing in the app gates a request on this — TMDB calls still just fail
 * with `MetadataError.Offline` — it exists so a screen showing that failure
 * can try again by itself when the connection comes back, instead of saying
 * "offline" on a phone that plainly is online until someone taps Retry.
 */
interface ConnectivityMonitor {
    /** The current state first, then every change. */
    val isOnline: Flow<Boolean>

    /** For targets with no signal to read, and for tests. */
    object AlwaysOnline : ConnectivityMonitor {
        override val isOnline: Flow<Boolean> = flowOf(true)
    }
}

/**
 * The platform's monitor: `NWPathMonitor` on iOS, the browser's `online` and
 * `offline` events on web, always online on desktop (no portable signal).
 * Android's needs a `Context`, so its actual is always-online too and
 * `MuvissApplication` binds `AndroidConnectivityMonitor` over it.
 */
expect class DefaultConnectivityMonitor() : ConnectivityMonitor {
    override val isOnline: Flow<Boolean>
}

/**
 * One emission each time the device goes from offline to online. Never for
 * the first value, so subscribing while online does nothing.
 */
fun ConnectivityMonitor.reconnections(): Flow<Unit> = flow {
    var wasOnline: Boolean? = null
    isOnline.collect { online ->
        if (online && wasOnline == false) emit(Unit)
        wasOnline = online
    }
}
