package com.codingpit.muviss.core.common.review

import com.codingpit.muviss.core.common.AppClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides whether the store review sheet may be asked for now, and asks it if so (#410).
 *
 * The decision is [ReviewPromptPolicy]'s, made from the ledger and the clock. The ledger is
 * written only when the prompter reports the request went out, so a device with nowhere to ask
 * keeps its eligibility. [invoke] runs under a lock: two titles completed in the same instant
 * must not both read "never asked" and both ask.
 */
class AskForReviewUseCase(
    private val ledger: ReviewLedger,
    private val prompter: ReviewPrompter,
    private val clock: AppClock,
    private val currentVersion: String,
) {
    private val lock = Mutex()

    /** Returns true when a review was requested this call. */
    suspend operator fun invoke(recentlyFailed: Boolean): Boolean = lock.withLock {
        val now = clock.nowEpochMs()
        if (!ReviewPromptPolicy.mayAsk(ledger.read(), now, currentVersion, recentlyFailed)) return@withLock false
        val requested = prompter.prompt()
        if (requested) ledger.recordAsk(now, currentVersion)
        requested
    }
}
