package com.codingpit.muviss.review

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.review.AskForReviewUseCase
import com.codingpit.muviss.core.common.review.ReviewLedger
import com.codingpit.muviss.core.common.review.ReviewPromptPolicy
import com.codingpit.muviss.core.common.review.ReviewPrompter
import com.codingpit.muviss.core.common.review.ReviewRecord
import com.codingpit.muviss.feature.progress.api.Completion
import com.codingpit.muviss.feature.progress.api.CompletionKind
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The #410 listener: stamps the install once, asks after a Completion, holds while a failure stands, and keeps listening after a failed ask. */
class ReviewPromptOnCompletionTest {

    private val day = 86_400_000L
    private val now = 5_000_000_000_000L
    private val completion = Completion(MediaId.tmdbMovie("1"), CompletionKind.FINISHED)

    private class FixedClock(private val nowMs: Long) : AppClock {
        override fun nowEpochMs(): Long = nowMs
    }

    private class MemoryLedger(var record: ReviewRecord) : ReviewLedger {
        val stamps = mutableListOf<Long>()
        override suspend fun read() = record
        override suspend fun stampInstalledIfUnknown(nowEpochMs: Long) {
            stamps += nowEpochMs
            if (record.installedAtEpochMs == null) record = record.copy(installedAtEpochMs = nowEpochMs)
        }
        override suspend fun countTick() = Unit
        override suspend fun recordAsk(nowEpochMs: Long, version: String) {
            record = record.copy(lastAskedAtEpochMs = nowEpochMs, lastAskedVersion = version)
        }
    }

    /** Installed long enough ago and enough ticks made, so only the failure or the prompter can hold the ask. */
    private fun eligibleLedger() = MemoryLedger(
        ReviewRecord(
            installedAtEpochMs = now - (ReviewPromptPolicy.MIN_INSTALL_DAYS + 1) * day,
            tickCount = ReviewPromptPolicy.MIN_TICKS,
            lastAskedAtEpochMs = null,
            lastAskedVersion = null,
        ),
    )

    private fun listener(
        completions: MutableSharedFlow<Completion>,
        ledger: ReviewLedger,
        prompter: ReviewPrompter,
        standingFailure: suspend () -> Boolean = { false },
    ) = ReviewPromptOnCompletion(
        completions = completions,
        standingFailure = standingFailure,
        ledger = ledger,
        clock = FixedClock(now),
        askForReview = AskForReviewUseCase(ledger, prompter, FixedClock(now), "1.4.0"),
    )

    @Test
    fun start_stamps_the_install_date_with_the_clock_reading() = runTest {
        val ledger = eligibleLedger()
        listener(MutableSharedFlow(extraBufferCapacity = 8), ledger, ReviewPrompter.None).start(backgroundScope)
        runCurrent()

        assertEquals(listOf(now), ledger.stamps)
    }

    @Test
    fun a_completion_asks_for_a_review_once_the_policy_allows_it() = runTest {
        val completions = MutableSharedFlow<Completion>(extraBufferCapacity = 8)
        val ledger = eligibleLedger()
        var prompts = 0
        listener(
            completions,
            ledger,
            ReviewPrompter {
                prompts++
                true
            },
        ).start(backgroundScope)
        runCurrent()

        completions.tryEmit(completion)
        runCurrent()

        assertEquals(1, prompts)
        assertNotNull(ledger.record.lastAskedAtEpochMs)
        assertEquals("1.4.0", ledger.record.lastAskedVersion)
    }

    @Test
    fun a_standing_sync_failure_holds_the_ask_even_when_the_policy_would_allow_it() = runTest {
        val completions = MutableSharedFlow<Completion>(extraBufferCapacity = 8)
        var prompts = 0
        listener(
            completions,
            eligibleLedger(),
            ReviewPrompter {
                prompts++
                true
            },
            standingFailure = { true },
        ).start(backgroundScope)
        runCurrent()

        completions.tryEmit(completion)
        runCurrent()

        assertEquals(0, prompts)
    }

    @Test
    fun a_failed_ask_is_reported_and_the_next_completion_is_still_heard() = runTest {
        val completions = MutableSharedFlow<Completion>(extraBufferCapacity = 8)
        val ledger = eligibleLedger()
        var calls = 0
        val prompter = ReviewPrompter {
            calls++
            if (calls == 1) error("the store refused the request")
            true
        }
        listener(completions, ledger, prompter).start(backgroundScope)
        runCurrent()

        completions.tryEmit(completion)
        runCurrent()
        completions.tryEmit(completion)
        runCurrent()

        assertEquals(2, calls)
        assertEquals("1.4.0", ledger.record.lastAskedVersion)
    }
}
