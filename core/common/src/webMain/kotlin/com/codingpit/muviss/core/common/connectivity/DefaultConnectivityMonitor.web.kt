package com.codingpit.muviss.core.common.connectivity

import kotlinx.browser.window
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.w3c.dom.events.Event

/**
 * `navigator.onLine` and its `online`/`offline` events. Shared by js and
 * wasmJs, like `DefaultSystemLocale`: both interop identically here.
 */
actual class DefaultConnectivityMonitor actual constructor() : ConnectivityMonitor {
    actual override val isOnline: Flow<Boolean> = callbackFlow {
        val listener: (Event) -> Unit = { trySend(window.navigator.onLine) }
        trySend(window.navigator.onLine)
        window.addEventListener("online", listener)
        window.addEventListener("offline", listener)
        awaitClose {
            window.removeEventListener("online", listener)
            window.removeEventListener("offline", listener)
        }
    }.distinctUntilChanged()
}
