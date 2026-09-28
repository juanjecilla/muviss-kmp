package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.common.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What runs a sync cycle. [SyncEngine] is the only production implementation; the seam is here so the coordinator's timing can be tested without a database. */
interface SyncRunner {
    suspend fun syncNow(trigger: SyncTrigger): SyncOutcome

    /**
     * When the last cycle that actually ran (whoever asked for it, success or
     * failure) finished, or null if none has since the process started.
     *
     * Deliberately process memory, not `syncState.lastAttemptAtEpochMs` (the
     * persisted value `observeStatus` reads for the Profile screen): a plain
     * property has to answer synchronously from [SyncCoordinator.ranRecently],
     * and a DB read here would need `suspend`, which the web driver forces
     * (issue #117, item 2 — "the 60s foreground skip window is in memory").
     * The cost is narrow: a cold start within the skip window syncs once more
     * than strictly necessary, which is what the window exists to avoid, not
     * a correctness problem — the run it allows through is still gated by
     * everything else in [SyncEngine.syncNow].
     */
    val lastFinishedAtEpochMs: Long?
}

/** The coordinator's clock-dependent constants, gathered so a test can read them rather than repeat them. */
data class SyncTiming(
    val debounce: Duration = 5.seconds,
    val minChangeInterval: Duration = 30.seconds,
    val backoffCap: Duration = 15.minutes,
    val foregroundSkipWindow: Duration = 60.seconds,
)

/**
 * Decides *when* a cycle is worth asking for (EPIC 40, ADR 0021); whether it
 * is *allowed* is [AutoSyncPolicy]'s job, enforced inside [SyncEngine], so
 * nothing here can widen what the switch permits.
 *
 * Three things ask through it:
 * - **Local writes**, watched through [pendingChanges] (the dirty-row count)
 *   only while [automaticEnabled] is true: debounced by [SyncTiming.debounce],
 *   at most one run per [SyncTiming.minChangeInterval], and on failure retried
 *   with exponential backoff capped at [SyncTiming.backoffCap]. A pull cannot
 *   feed this back into itself, because pulled rows are written clean and so
 *   never raise the count.
 * - **The app coming forward** ([onForeground], [onResume]), skipped when a
 *   cycle finished within [SyncTiming.foregroundSkipWindow] — `ON_START` and
 *   `ON_RESUME` fire back to back, and so does a browser tab regaining focus.
 * - **A timer or the OS** ([runPeriodic]), which is never skipped: the
 *   scheduler already decided this was the moment.
 *
 * It lives for the process, on the [scope] it is given. Nothing survives a
 * process death, which is the point of the "only while the process is alive"
 * rule: WorkManager and `BGAppRefreshTask` cover the times it is not.
 */
class SyncCoordinator(
    private val runner: SyncRunner,
    private val pendingChanges: Flow<Long>,
    private val automaticEnabled: Flow<Boolean>,
    private val clock: AppClock,
    private val scope: CoroutineScope,
    private val timing: SyncTiming = SyncTiming(),
) {
    /** Bookkeeping for the change loop, in one atomically-updated value: it is touched from whichever thread a caller happens to be on. */
    private data class Backoff(val consecutiveFailures: Int = 0, val nextChangeRunAtMs: Long = 0L)

    private val backoff = MutableStateFlow(Backoff())
    private val started = MutableStateFlow(false)
    private val skippableRunInFlight = MutableStateFlow(false)

    /** Begins watching local writes. Idempotent: every host that has a process to keep alive may call it. */
    fun start() {
        if (!started.compareAndSet(expect = false, update = true)) return
        scope.launch {
            automaticEnabled.distinctUntilChanged().collectLatest { enabled ->
                if (enabled) watchChanges()
            }
        }
    }

    /** The app came to the foreground. Fire-and-forget: the outcome is on `syncState`, where the Profile screen reads it. */
    fun onForeground() = requestSkippable(SyncTrigger.Foreground)

    /** A web tab became visible or the browser came back online. Shares [onForeground]'s skip window. */
    fun onResume() = requestSkippable(SyncTrigger.Resume)

    /** A timer or OS tick. Not subject to the skip window. */
    suspend fun runPeriodic(): SyncOutcome = runner.syncNow(SyncTrigger.Periodic).also(::noteOutcome)

    // --- foreground -------------------------------------------------------------

    private fun requestSkippable(trigger: SyncTrigger) {
        scope.launch {
            if (!skippableRunInFlight.compareAndSet(expect = false, update = true)) return@launch
            try {
                if (!ranRecently()) noteOutcome(runner.syncNow(trigger))
            } finally {
                skippableRunInFlight.value = false
            }
        }
    }

    private fun ranRecently(): Boolean {
        val last = runner.lastFinishedAtEpochMs ?: return false
        return clock.nowEpochMs() - last < timing.foregroundSkipWindow.inWholeMilliseconds
    }

    // --- local writes -------------------------------------------------------------

    @OptIn(FlowPreview::class)
    private suspend fun watchChanges() {
        pendingChanges
            .distinctUntilChanged()
            .debounce(timing.debounce)
            .filter { it > 0L }
            .collect { pushWhenAllowed() }
    }

    /**
     * Runs until the rows are sent, waiting out the rate limit and any backoff
     * first. Looping *here*, inside the collector, is what makes a failed run
     * retry without a new write to prompt it — and what lets a burst of writes
     * during the wait coalesce into that same retry, since the debounce keeps
     * only the latest value while this is busy.
     */
    private suspend fun pushWhenAllowed() {
        while (true) {
            val wait = backoff.value.nextChangeRunAtMs - clock.nowEpochMs()
            if (wait > 0L) delay(wait)
            // Another trigger may have sent everything while this waited.
            if (pendingChanges.first() <= 0L) return

            val startedAt = clock.nowEpochMs()
            if (!shouldRetry(runner.syncNow(SyncTrigger.Change), startedAt)) return
        }
    }

    /** Records a change-triggered run's outcome. True when the same rows should be tried again after a backoff. */
    private fun shouldRetry(outcome: SyncOutcome, startedAt: Long): Boolean {
        val rateLimited = startedAt + timing.minChangeInterval.inWholeMilliseconds
        return when {
            outcome is SyncOutcome.Success -> {
                backoff.update { Backoff(consecutiveFailures = 0, nextChangeRunAtMs = rateLimited) }
                false
            }

            // A dead session: no amount of waiting fixes it, and the next write
            // (or a sign-in) is the next reason to try.
            outcome is SyncOutcome.Failed && outcome.reason != SyncFailureReason.Unauthorised -> {
                backoff.update {
                    val failures = it.consecutiveFailures + 1
                    Backoff(consecutiveFailures = failures, nextChangeRunAtMs = clock.nowEpochMs() + backoffFor(failures))
                }
                true
            }

            // Disabled, signed out, unentitled, an account change or a dead
            // session: nothing was attempted or nothing can be. Retrying on a
            // timer would only hammer a condition the user has to change.
            else -> {
                backoff.update { it.copy(nextChangeRunAtMs = rateLimited) }
                false
            }
        }
    }

    /** A run that was not change-triggered still says the server is reachable; it clears the failure streak. */
    private fun noteOutcome(outcome: SyncOutcome) {
        if (outcome is SyncOutcome.Success) backoff.update { it.copy(consecutiveFailures = 0) }
    }

    private fun backoffFor(consecutiveFailures: Int): Long {
        val cap = timing.backoffCap.inWholeMilliseconds
        var delayMs = timing.minChangeInterval.inWholeMilliseconds
        repeat(consecutiveFailures - 1) { delayMs = minOf(delayMs * 2, cap) }
        return minOf(delayMs, cap)
    }
}
