package com.codingpit.muviss.core.common.review

import com.codingpit.muviss.core.common.AppClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskForReviewUseCaseTest {

    private val day = 86_400_000L
    private val installed = 1_000_000_000_000L
    private val now = installed + ReviewPromptPolicy.MIN_INSTALL_DAYS * day
    private val version = "1.4.0"

    private class FixedClock(var nowMs: Long) : AppClock {
        override fun nowEpochMs(): Long = nowMs
    }

    private class MemoryLedger(var record: ReviewRecord) : ReviewLedger {
        override suspend fun read() = record
        override suspend fun stampInstalledIfUnknown(nowEpochMs: Long) = Unit
        override suspend fun countTick() = Unit
        override suspend fun recordAsk(nowEpochMs: Long, version: String) {
            record = record.copy(lastAskedAtEpochMs = nowEpochMs, lastAskedVersion = version)
        }
    }

    private class CountingPrompter(var answer: Boolean = true) : ReviewPrompter {
        var calls = 0
        override suspend fun prompt(): Boolean {
            calls++
            return answer
        }
    }

    private fun eligibleLedger() = MemoryLedger(
        ReviewRecord(installedAtEpochMs = installed, tickCount = ReviewPromptPolicy.MIN_TICKS, lastAskedAtEpochMs = null, lastAskedVersion = null),
    )

    @Test
    fun an_eligible_device_is_asked_and_the_ask_is_recorded() = runTest {
        val ledger = eligibleLedger()
        val prompter = CountingPrompter(answer = true)

        val asked = AskForReviewUseCase(ledger, prompter, FixedClock(now), version)(recentlyFailed = false)

        assertTrue(asked)
        assertEquals(1, prompter.calls)
        assertEquals(now, ledger.record.lastAskedAtEpochMs)
        assertEquals(version, ledger.record.lastAskedVersion)
    }

    @Test
    fun an_ineligible_device_is_not_asked_and_the_prompter_is_never_touched() = runTest {
        val ledger = eligibleLedger()
        val prompter = CountingPrompter()

        val asked = AskForReviewUseCase(ledger, prompter, FixedClock(installed), version)(recentlyFailed = false)

        assertFalse(asked)
        assertEquals(0, prompter.calls)
        assertNull(ledger.record.lastAskedAtEpochMs)
    }

    @Test
    fun a_request_the_platform_could_not_take_records_nothing_so_the_device_stays_eligible() = runTest {
        val ledger = eligibleLedger()
        val prompter = CountingPrompter(answer = false)

        val asked = AskForReviewUseCase(ledger, prompter, FixedClock(now), version)(recentlyFailed = false)

        assertFalse(asked)
        assertEquals(1, prompter.calls)
        assertNull(ledger.record.lastAskedAtEpochMs)
        assertNull(ledger.record.lastAskedVersion)
    }

    @Test
    fun a_standing_failure_stops_the_ask_before_the_prompter() = runTest {
        val prompter = CountingPrompter()

        val asked = AskForReviewUseCase(eligibleLedger(), prompter, FixedClock(now), version)(recentlyFailed = true)

        assertFalse(asked)
        assertEquals(0, prompter.calls)
    }

    @Test
    fun two_completions_at_once_ask_once_because_the_second_reads_the_first_ask() = runTest {
        val ledger = eligibleLedger()
        val gate = CompletableDeferred<Unit>()
        val prompter = object : ReviewPrompter {
            var calls = 0
            override suspend fun prompt(): Boolean {
                calls++
                gate.await()
                return true
            }
        }
        val useCase = AskForReviewUseCase(ledger, prompter, FixedClock(now), version)

        val first = async { useCase(recentlyFailed = false) }
        val second = async { useCase(recentlyFailed = false) }
        gate.complete(Unit)

        assertTrue(first.await())
        assertFalse(second.await())
        assertEquals(1, prompter.calls)
    }
}
