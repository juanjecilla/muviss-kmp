package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.widget.AppWidgets
import com.codingpit.muviss.core.common.widget.WidgetRefresher
import com.codingpit.muviss.core.database.MuvissDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** The outcome of one [SyncEngine.syncNow] run. */
sealed interface SyncOutcome {
    /** No [SyncBackend] session exists — sync was skipped, not attempted. Not an error: this is the default, pre-sign-in state. */
    data object NotSignedIn : SyncOutcome

    /**
     * [EntitlementGate] said no — sync was skipped, not attempted, and not an
     * error either. Distinct from [NotSignedIn] because the two need opposite
     * things from the user: one a sign-in, the other a purchase.
     */
    data object NotEntitled : SyncOutcome

    /**
     * The signed-in account is not the one this device's library belongs to
     * ([previousAccountId]). Nothing was pushed or pulled: sending one person's
     * library into another's account, or merging two, is not a decision the
     * engine makes on its own. The caller decides and answers with
     * [SyncEngine.resolveAccountChange].
     */
    data class AccountChanged(val previousAccountId: String, val currentAccountId: String) : SyncOutcome

    data class Success(val pushedCount: Int, val pulledCount: Int, val syncedAtEpochMs: Long) : SyncOutcome

    /**
     * An automatic trigger asked, and the build or the user's "sync automatically"
     * switch said no ([AutoSyncPolicy]). Nothing was attempted. Never returned
     * for [SyncTrigger.Manual].
     */
    data object Disabled : SyncOutcome

    /**
     * The cycle ran and failed. [reason] is what a screen keys its copy off;
     * [detail] is the exception text, for a log — it is never shown to a person,
     * because it is whatever a socket or a proxy happened to say.
     */
    data class Failed(val reason: SyncFailureReason, val detail: String) : SyncOutcome
}

/** What to do with a library that belongs to a different account than the one now signed in. See [SyncEngine.resolveAccountChange]. */
enum class AccountChangeResolution {
    /** Delete this device's library and take the new account's. The safe default: nothing of the previous person survives on the device. */
    DiscardLocalData,

    /** Keep this device's library and merge it into the new account, as if it had always been theirs. */
    MergeLocalDataIntoAccount,
}

/**
 * Replays local dirty rows to [backend] and merges remote changes back in —
 * the concrete implementation of the "SyncEngine" abstraction CONTEXT.md and
 * ADR 0002 named before any backend existed. See ADR 0009 for the full
 * design, ADR 0020 for what changed in EPIC 39, and docs/SYNC.md for the
 * schema/RLS this expects server-side.
 *
 * **Order**: push local dirty rows first, then pull. A push is safe to
 * attempt unconditionally (never gated on first reading the remote value)
 * because the backend's own last-write-wins guard — a Postgres trigger, see
 * docs/SYNC.md — silently discards a push whose `updated_at_epoch_ms` is
 * older than what's already stored, rather than this engine needing to
 * read-before-write. The pull side applies the same rule client-side (see
 * [RemoteApplier]), so tombstones (`deleted = true`) propagate exactly like
 * any other field and a stale delete/undelete can never resurrect a newer
 * local write, or vice versa.
 *
 * **Whose data this is.** `syncState.ownerAccountId` records the account the
 * local library belongs to. The first sync from a database with no owner
 * adopts the signed-in account and puts every local row on the change-log —
 * which is also the one full reconcile an upgraded install needs. A different
 * account than the recorded one stops the cycle with
 * [SyncOutcome.AccountChanged] until [resolveAccountChange] says what to do.
 *
 * Conflict granularity is the whole row, not per-field — simple and
 * predictable, matching CONTEXT.md's "episode ticks are idempotent
 * booleans" note (no merge needed there beyond LWW) and accepted as a
 * known, documented limitation for the richer rows (ADR 0009).
 */
class SyncEngine(
    private val backend: SyncBackend,
    private val database: MuvissDatabase,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
    private val entitlementGate: EntitlementGate = EntitlementGate.AlwaysEntitled,
    private val widgetRefresher: WidgetRefresher = AppWidgets,
) {
    private val changeLog = LocalChangeLog(database)
    private val applier = RemoteApplier(database)

    /**
     * One cycle at a time. Two callers can otherwise overlap — `MuvissApp`'s
     * `AutoSyncOnForeground` fires on every `ON_START` while the profile
     * screen's "Sync now" button is a tap away — and interleaving them would
     * push and pull the same rows twice over. The second caller waits rather
     * than returning early, so a manual tap still reflects everything the user
     * just did.
     *
     * The mutex serialises *syncs*, not *writes*: the user can still edit a
     * row while a cycle is in flight. That is what the conditional
     * `clearDirty` is for.
     */
    private val syncMutex = Mutex()

    /** The last time [syncNow] completed a full cycle, or null if it never has. Backs the profile screen's "last synced" label. */
    fun observeLastSyncedAt(): Flow<Long?> = database.appSettingsQueries.selectSettings()
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .map { it?.lastSyncedAtEpochMs }

    suspend fun syncNow(): SyncOutcome = serialised { userId -> guarded { syncCycle(userId) } }

    /**
     * Answers a [SyncOutcome.AccountChanged] and then syncs. A no-op resolution
     * if the owner is not actually different by now (another caller resolved it
     * first), so it is safe to call twice.
     */
    suspend fun resolveAccountChange(resolution: AccountChangeResolution): SyncOutcome = serialised { userId ->
        guarded {
            val owner = currentOwner()
            if (owner != null && owner != userId) {
                when (resolution) {
                    AccountChangeResolution.DiscardLocalData -> discardLocalData(userId)
                    AccountChangeResolution.MergeLocalDataIntoAccount -> adoptAccount(userId)
                }
            }
            syncCycle(userId)
        }
    }

    /**
     * The repair path: forgets where every pull stopped, puts every local row
     * back on the change-log, then runs a normal cycle — a full pull, a full
     * push and a full reconciliation. It is also the fallback if the server's
     * sequence ever leaves a gap an incremental pull cannot see (ADR 0020).
     * Idempotent, and slow in proportion to the library.
     */
    suspend fun resyncEverything(): SyncOutcome = serialised { userId ->
        guarded {
            syncCycle(userId) {
                database.transaction {
                    changeLog.markAllDirty()
                    changeLog.resetCursors()
                }
            }
        }
    }

    /** Gates, then runs [block] with the signed-in account's id, one cycle at a time. */
    private suspend fun serialised(block: suspend (userId: String) -> SyncOutcome): SyncOutcome = withContext(dispatchers.io) {
        val session = backend.session.first() ?: return@withContext SyncOutcome.NotSignedIn
        if (!entitlementGate.isEntitled()) return@withContext SyncOutcome.NotEntitled
        syncMutex.withLock { block(session.userId) }
    }

    /**
     * Turns a failure into [SyncOutcome.Failed] and records it — but never a
     * cancellation. A [CancellationException] is how a coroutine is told to
     * stop, so swallowing it into a "failed" result both lies to the caller
     * and counts a user backing out as a sync failure.
     */
    private suspend fun guarded(block: suspend () -> SyncOutcome): SyncOutcome = runCatching { block() }.getOrElse { failure ->
        if (failure is CancellationException) throw failure
        recordFailure(failure)
        SyncOutcome.Failed(SyncFailureReason.classify(failure), failure.message ?: "Sync failed")
    }

    private suspend fun syncCycle(userId: String, beforePush: suspend () -> Unit = {}): SyncOutcome {
        database.syncStateQueries.ensureRow()
        val owner = currentOwner()
        when {
            owner == null -> adoptAccount(userId)
            owner != userId -> return SyncOutcome.AccountChanged(previousAccountId = owner, currentAccountId = userId)
        }
        beforePush()
        database.appSettingsQueries.ensureRow()

        val pushed = changeLog.pushDirty { backend.push(it) }

        val cursors = changeLog.loadCursors()
        val advanced = cursors.toMutableMap()
        val touched = mutableSetOf<String>()
        var pulled = 0
        backend.pull(cursors) { page ->
            pulled += page.changes.size
            touched += applier.applyPage(page)
            advanced[page.table] = page.cursor
        }.getOrThrow()
        // Cursors move only now — after every table drained and reconciled, in
        // that last transaction. A pull that fails anywhere leaves all six where
        // they were, and the retry re-applies the same rows to the same result.
        applier.reconcile(touched) { changeLog.saveCursors(advanced) }
        if (pulled > 0) refreshWidgets()

        // "Last synced" answers "when did this device last complete a cycle",
        // and must advance even when the pull came back empty.
        val now = clock.nowEpochMs()
        database.appSettingsQueries.updateLastSyncedAt(now)
        database.syncStateQueries.recordSuccess(now)
        return SyncOutcome.Success(pushedCount = pushed, pulledCount = pulled, syncedAtEpochMs = now)
    }

    private suspend fun currentOwner(): String? = database.syncStateQueries.selectState().awaitAsOneOrNull()?.ownerAccountId

    /** Makes [userId] the owner of whatever is on this device, and puts all of it on the change-log for the full reconcile that follows. */
    private suspend fun adoptAccount(userId: String) {
        database.transaction {
            changeLog.markAllDirty()
            changeLog.resetCursors()
            database.syncStateQueries.setOwner(userId)
        }
    }

    private suspend fun discardLocalData(userId: String) {
        database.transaction {
            changeLog.deleteAllUserData()
            changeLog.resetCursors()
            database.syncStateQueries.setOwner(userId)
        }
        refreshWidgets()
    }

    private suspend fun recordFailure(failure: Throwable) {
        runCatching {
            database.syncStateQueries.ensureRow()
            database.syncStateQueries.recordFailure(error = failure.message ?: failure::class.simpleName, attemptedAtEpochMs = clock.nowEpochMs())
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** A stale widget is a stale row on a home screen; it must not turn a completed sync into a failed one. */
    private suspend fun refreshWidgets() {
        runCatching { widgetRefresher.refresh() }.onFailure { if (it is CancellationException) throw it }
    }
}
