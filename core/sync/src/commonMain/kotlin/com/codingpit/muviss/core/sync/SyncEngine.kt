package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.widget.AppWidgets
import com.codingpit.muviss.core.common.widget.WidgetRefresher
import com.codingpit.muviss.core.database.MuvissDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
@Suppress("LongParameterList") // a composition root for one service: the optional ones default to "off" so callers name only what they use
class SyncEngine(
    private val backend: SyncBackend,
    private val database: MuvissDatabase,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
    private val entitlementGate: EntitlementGate = EntitlementGate.AlwaysEntitled,
    private val widgetRefresher: WidgetRefresher = AppWidgets,
    private val automatic: AutomaticSyncSettings = AutomaticSyncSettings(),
) : SyncRunner {
    private val availability get() = automatic.availability
    private val syncAutomatically get() = automatic.switch

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

    private val lastFinished = MutableStateFlow<Long?>(null)

    override val lastFinishedAtEpochMs: Long? get() = lastFinished.value

    /** A manual sync: what the "Sync now" button and the end of sign-in ask for. */
    suspend fun syncNow(): SyncOutcome = syncNow(SyncTrigger.Manual)

    /**
     * Runs one cycle for [trigger], if [AutoSyncPolicy] allows it — the one
     * place the automatic-sync switch, the build flag, the session and the
     * entitlement are all enforced (ADR 0018, ADR 0021). Every automatic
     * trigger that fails the policy returns [SyncOutcome.Disabled] (or
     * [SyncOutcome.NotSignedIn] / [SyncOutcome.NotEntitled]) having touched
     * neither the database nor the network.
     */
    override suspend fun syncNow(trigger: SyncTrigger): SyncOutcome = serialised(trigger) { userId -> guarded { syncCycle(userId) } }

    /**
     * Where the last attempt stands, for the Profile status line: when this
     * device last synced, whether the last attempt failed and why, and how many
     * local changes are waiting to be sent.
     */
    fun observeStatus(): Flow<SyncStatusSnapshot> = combine(
        database.syncStateQueries.selectState().asFlow().mapToOneOrNull(dispatchers.io),
        observeLastSyncedAt(),
        observePendingChanges(),
    ) { state, lastSyncedAt, pending ->
        val failed = state?.lastOutcome == OUTCOME_FAILED
        SyncStatusSnapshot(
            lastSyncedAtEpochMs = lastSyncedAt,
            lastAttemptAtEpochMs = state?.lastAttemptAtEpochMs,
            lastAttemptFailed = failed,
            lastFailure = if (failed) SyncFailureReason.fromStored(state?.lastError) ?: SyncFailureReason.Unknown else null,
            consecutiveFailures = state?.consecutiveFailures?.toInt() ?: 0,
            pendingChanges = pending,
        )
    }.distinctUntilChanged()

    /** How many local rows are waiting to be sent, across all synced tables. */
    fun observePendingChanges(): Flow<Long> = database.syncStateQueries.countDirtyRows().asFlow().mapToOne(dispatchers.io).distinctUntilChanged()

    /** Forgets the last failure. Called on an explicit sign-out, so a "session expired" notice does not outlive the choice to leave. */
    suspend fun forgetLastFailure() = withContext(dispatchers.io) {
        database.syncStateQueries.ensureRow()
        database.syncStateQueries.clearFailure()
    }

    /**
     * Answers a [SyncOutcome.AccountChanged] and then syncs. A no-op resolution
     * if the owner is not actually different by now (another caller resolved it
     * first), so it is safe to call twice.
     */
    suspend fun resolveAccountChange(resolution: AccountChangeResolution): SyncOutcome = serialised(SyncTrigger.Manual) { userId ->
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
    suspend fun resyncEverything(): SyncOutcome = serialised(SyncTrigger.Manual) { userId ->
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
    private suspend fun serialised(trigger: SyncTrigger, block: suspend (userId: String) -> SyncOutcome): SyncOutcome = withContext(dispatchers.io) {
        val session = backend.session.first()
        val available = availability.isBackgroundAvailable()
        val switchOn = trigger.isAutomatic && available && syncAutomatically.first()
        // The entitlement gate can be a store lookup, so it is only asked once
        // an automatic trigger has got past the build flag and the switch.
        val mayAsk = !trigger.isAutomatic || switchOn
        val decision = AutoSyncPolicy.decide(
            trigger = trigger,
            backgroundAvailable = available,
            switchOn = switchOn,
            signedIn = session != null,
            entitled = mayAsk && session != null && entitlementGate.isEntitled(),
        )
        when (decision) {
            SyncDecision.Disabled -> SyncOutcome.Disabled

            SyncDecision.NotSignedIn -> SyncOutcome.NotSignedIn

            SyncDecision.NotEntitled -> SyncOutcome.NotEntitled

            SyncDecision.Run -> syncMutex.withLock {
                // Asked again after the wait: an automatic run queued behind a
                // long manual sync must not go ahead if the switch was turned
                // off meanwhile.
                if (trigger.isAutomatic && !syncAutomatically.first()) {
                    SyncOutcome.Disabled
                } else {
                    block(checkNotNull(session).userId).also { lastFinished.value = clock.nowEpochMs() }
                }
            }
        }
    }

    /**
     * Turns a failure into [SyncOutcome.Failed] and records it — but never a
     * cancellation. A [CancellationException] is how a coroutine is told to
     * stop, so swallowing it into a "failed" result both lies to the caller
     * and counts a user backing out as a sync failure.
     */
    private suspend fun guarded(block: suspend () -> SyncOutcome): SyncOutcome = runCatching { block() }.getOrElse { failure ->
        if (failure is CancellationException) throw failure
        val reason = SyncFailureReason.classify(failure)
        val detail = failure.message ?: failure::class.simpleName ?: "Sync failed"
        recordFailure(reason, detail)
        SyncOutcome.Failed(reason, detail)
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

        var cursors = changeLog.loadCursors()
        val now = clock.nowEpochMs()
        // The bound on the sequence-gap risk (ADR 0020): a commit that lands
        // out of order can hide a row from an incremental pull, so once a week
        // this device pulls from the beginning.
        if (cursors.isNotEmpty() && FullPullSchedule.isDue(changeLog.lastFullPullAt(), now)) {
            changeLog.resetCursors()
            cursors = emptyMap()
        }
        val fullPull = cursors.isEmpty()
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
        applier.reconcile(touched) {
            changeLog.saveCursors(advanced)
            if (fullPull) changeLog.recordFullPull(now)
        }
        if (pulled > 0) refreshWidgets()

        // "Last synced" answers "when did this device last complete a cycle",
        // and must advance even when the pull came back empty.
        val finishedAt = clock.nowEpochMs()
        database.appSettingsQueries.updateLastSyncedAt(finishedAt)
        database.syncStateQueries.recordSuccess(finishedAt)
        return SyncOutcome.Success(pushedCount = pushed, pulledCount = pulled, syncedAtEpochMs = finishedAt)
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

    private suspend fun recordFailure(reason: SyncFailureReason, detail: String) {
        runCatching {
            database.syncStateQueries.ensureRow()
            database.syncStateQueries.recordFailure(error = SyncFailureReason.encode(reason, detail), attemptedAtEpochMs = clock.nowEpochMs())
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** A stale widget is a stale row on a home screen; it must not turn a completed sync into a failed one. */
    private suspend fun refreshWidgets() {
        runCatching { widgetRefresher.refresh() }.onFailure { if (it is CancellationException) throw it }
    }
}

private const val OUTCOME_FAILED = "FAILED"

/** What [SyncEngine.observeStatus] reports; everything the Profile status line needs, and no copy. */
data class SyncStatusSnapshot(
    val lastSyncedAtEpochMs: Long?,
    val lastAttemptAtEpochMs: Long?,
    val lastAttemptFailed: Boolean,
    val lastFailure: SyncFailureReason?,
    val consecutiveFailures: Int,
    val pendingChanges: Long,
)

/**
 * The two inputs to [AutoSyncPolicy] that are not the session or the
 * entitlement: whether the build ships background sync, and the per-device
 * switch. Defaults to "no", so an engine built without them can only ever run
 * [SyncTrigger.Manual].
 */
class AutomaticSyncSettings(
    val availability: SyncAvailability = SyncAvailability { false },
    val switch: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
)
