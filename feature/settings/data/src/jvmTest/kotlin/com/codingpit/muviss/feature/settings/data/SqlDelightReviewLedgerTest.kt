package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.review.ReviewRecord
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class ImmediateReviewDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

/** The #410 ledger over the real `appSettings` columns: defaults, the one-time install stamp, counting, and the ask record. */
class SqlDelightReviewLedgerTest {

    private val database = inMemoryDatabase()
    private val ledger = SqlDelightReviewLedger(database.appSettingsQueries, ImmediateReviewDispatchers(UnconfinedTestDispatcher()))

    @Test
    fun a_fresh_device_reads_as_never_stamped_with_no_ticks_and_no_ask() = runTest {
        assertEquals(ReviewRecord(installedAtEpochMs = null, tickCount = 0, lastAskedAtEpochMs = null, lastAskedVersion = null), ledger.read())
    }

    @Test
    fun the_install_date_is_stamped_once_and_a_later_stamp_does_not_move_it() = runTest {
        ledger.stampInstalledIfUnknown(1_000L)
        ledger.stampInstalledIfUnknown(9_000L)

        assertEquals(1_000L, ledger.read().installedAtEpochMs)
    }

    @Test
    fun every_hand_tick_is_counted_and_the_count_is_exact() = runTest {
        repeat(25) { ledger.countTick() }

        assertEquals(25, ledger.read().tickCount)
    }

    @Test
    fun the_ask_records_when_and_which_version_so_the_policy_can_read_them_back() = runTest {
        ledger.recordAsk(4_242L, "1.4.0")

        val record = ledger.read()
        assertEquals(4_242L, record.lastAskedAtEpochMs)
        assertEquals("1.4.0", record.lastAskedVersion)
    }

    @Test
    fun counting_a_tick_leaves_the_install_date_and_the_ask_alone() = runTest {
        ledger.stampInstalledIfUnknown(1_000L)
        ledger.countTick()

        val record = ledger.read()
        assertEquals(1_000L, record.installedAtEpochMs)
        assertNull(record.lastAskedAtEpochMs)
    }
}
