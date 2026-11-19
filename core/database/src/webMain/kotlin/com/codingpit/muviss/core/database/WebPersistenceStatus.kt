package com.codingpit.muviss.core.database

import kotlinx.browser.window
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

    /**
     * [reloadRequested] is the worker deciding this tab is out of date and holding
     * nothing worth keeping (#62): it reloads now, rather than leaving a banner for
     * a state the reload fixes on its own.
     */
    fun report(
        writer: Boolean,
        webLocksSupported: Boolean,
        lastWriteFailed: Boolean,
        stale: Boolean,
        reloadRequested: Boolean,
    ) {
        mutable.value = persistenceStateOf(writer, webLocksSupported, lastWriteFailed, stale)
        if (reloadRequested) reloadPage()
    }

    override fun reloadPage() {
        window.location.reload()
    }
}
