package com.codingpit.muviss.review

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.crash.reportFailure
import com.codingpit.muviss.core.common.review.AskForReviewUseCase
import com.codingpit.muviss.core.common.review.ReviewLedger
import com.codingpit.muviss.feature.progress.api.Completion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow

/**
 * Listens for the person's own Completions and asks the store for a review after them (#410).
 *
 * Lives at app scope, started from `MuvissApp` the way `AutoSyncOnForeground` is, because a
 * Completion can come from any tab and the prompt belongs to none of them. The stream is
 * `ProgressApi.completions`, which only the in-app tick path emits (#397): an import, a sync pull
 * or a widget tick never reaches here, so it can never ask on the person's behalf.
 *
 * Start-up also stamps the install date, once, so the policy has a date to count from.
 *
 * [standingFailure] answers "is a failure still standing" (a sync failure, cleared by the next
 * successful cycle). It is a probe rather than a `SyncEngine` so this class stays testable; the
 * Koin wiring in `reviewPromptModule` supplies the real one. A failed tick never emits a
 * Completion, so it needs no check of its own. There is no app-wide error signal to read, so a
 * failure elsewhere does not hold the ask; that gap is a follow-up.
 *
 * Each Completion is handled on its own: one failed ask is reported and the next Completion is
 * still heard, so a single bad write cannot silence the listener for the rest of the session.
 */
class ReviewPromptOnCompletion(
    private val completions: Flow<Completion>,
    private val standingFailure: suspend () -> Boolean,
    private val ledger: ReviewLedger,
    private val clock: AppClock,
    private val askForReview: AskForReviewUseCase,
) {
    /** Starts listening in [scope]. The returned job ends with that scope, so leaving the composition stops it. */
    fun start(scope: CoroutineScope): Job = scope.launchReporting {
        runCatching { ledger.stampInstalledIfUnknown(clock.nowEpochMs()) }.reportFailure()
        completions.collect {
            runCatching { askForReview(recentlyFailed = standingFailure()) }.reportFailure()
        }
    }
}
