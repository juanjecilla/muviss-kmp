package com.codingpit.muviss.core.database

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The web half of [PersistenceStatus]: a mailbox the worker posts into.
 *
 * Starts at [PersistenceState.Pending] and stays there until the worker's
 * election resolves, which is what keeps the writer tab — the common case —
 * from flashing a warning banner on every page load.
 *
 * Shared by `js` and `wasmJs` from `webMain`. What cannot be shared is the
 * listener that feeds it: reaching into `event.data.muvissPersistence` needs a
 * `js(...)` snippet, and the two targets disagree about what type that snippet
 * may take (`Any?` on js, `JsAny` on wasmJs) — the same split that already
 * keeps `DatabaseFactory.js.kt` and `DatabaseFactory.wasmJs.kt` apart.
 */
internal class WebPersistenceStatus : PersistenceStatus {
    private val mutable = MutableStateFlow<PersistenceState>(PersistenceState.Pending)

    override val state: StateFlow<PersistenceState> = mutable.asStateFlow()

    fun report(writer: Boolean, webLocksSupported: Boolean) {
        mutable.value = persistenceStateOf(writer, webLocksSupported)
    }
}
