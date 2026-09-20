package com.codingpit.muviss.core.database

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether writes made in this session will still be here next time.
 *
 * On Android, iOS and desktop the answer is always yes and this exists only so
 * the question can be asked from `commonMain`. On web it is a real question
 * with three answers, because only one browser tab persists (ADR 0008, EPIC
 * 24): the tab holding the `muviss-db` Web Lock snapshots to IndexedDB, and
 * any other tab runs normally in memory and never writes.
 *
 * Before this, the other tabs were not told. A second tab looked identical to
 * the first, accepted ticks and library changes exactly the same way, and lost
 * all of them on reload — the only place in the app where a write is not
 * durable, and invisible (issue #53).
 */
sealed interface PersistenceState {

    /**
     * Not known yet — the lock has been requested and has not answered.
     *
     * Deliberately distinct from [Durable] rather than folded into it, so the
     * banner can render nothing while it is unknown. The writer tab is the
     * common case and reaches [Durable] within milliseconds; treating unknown
     * as "not durable" would flash a warning on every ordinary page load.
     */
    data object Pending : PersistenceState

    /** This session's writes are being persisted. Every native platform, always. */
    data object Durable : PersistenceState

    /** Another tab holds the write lock. This one works normally and saves nothing. */
    data object ReadOnlyTab : PersistenceState

    /**
     * Nothing persists anywhere: the browser has no Web Locks, so no tab was
     * elected and every tab is session-only. A private window with site data
     * blocked is the usual cause.
     */
    data object NotPersisted : PersistenceState
}

/**
 * Turns the worker's two booleans into a [PersistenceState].
 *
 * In `commonMain` rather than beside the web driver purely so it can be tested:
 * everything around it needs a live Web Worker, and `jvmTest` — where this
 * repo's tests run — has no browser to give it. `schemaStepFor` is pulled out
 * of `SchemaEnsuringDriver` for exactly the same reason.
 *
 * Order matters. "Not the writer" and "no Web Locks at all" both mean nothing
 * is being saved, but they are different sentences to a user: one says another
 * tab has it, the other says this browser cannot do it anywhere. Support is
 * checked first because when Web Locks is missing nobody is the writer, so
 * `writer` is `false` for an uninteresting reason.
 */
internal fun persistenceStateOf(writer: Boolean, webLocksSupported: Boolean): PersistenceState = when {
    !webLocksSupported -> PersistenceState.NotPersisted
    writer -> PersistenceState.Durable
    else -> PersistenceState.ReadOnlyTab
}

/** Reports [PersistenceState]. Bound in Koin by the platform that knows the answer. */
interface PersistenceStatus {
    val state: StateFlow<PersistenceState>
}

/**
 * The answer for every platform whose database is a file it owns outright.
 *
 * A `data object` rather than an `expect val`, and resolved through Koin rather
 * than through `expect`/`actual`, for the reason `CLAUDE.md` records about
 * `OAuthRedirectTarget`: a JVM actual hard-coding [PersistenceState.Durable]
 * would make the banner impossible to render in a `jvmTest`, which is the only
 * place Compose tests run in this repo.
 */
data object AlwaysDurable : PersistenceStatus {
    override val state: StateFlow<PersistenceState> = MutableStateFlow(PersistenceState.Durable).asStateFlow()
}
