package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #1085: the pull's reconciliation fills a seen tick that has no live viewing, at the id `episodeId@progress.updatedAt`.
 * A viewing removed earlier can carry that same id, and filling the gap must not bring it back.
 */
class RemoteApplierPlayRevivalTest {

    @Test
    fun `reconciliation does not revive a removed viewing whose id is the seen tick's stamp`() = runTest {
        val database = inMemoryDatabase()
        database.episodeProgressQueries.upsert(EPISODE, SERIES, 1L, 1L, true, 1_000L, false)
        database.episodePlayQueries.upsert(PLAY_ID, EPISODE, SERIES, 1_000L, 2_000L, false, true)

        RemoteApplier(database, FakeClock(3_000L)).reconcile(setOf(SERIES)) {}

        assertTrue(database.episodePlayQueries.selectById(PLAY_ID).awaitAsOneOrNull()!!.deleted)
        assertTrue(database.episodePlayQueries.selectAll().awaitAsList().isEmpty())
    }

    private companion object {
        const val SERIES = "tmdb:tv:1399"
        const val EPISODE = "tmdb:tv:1399/1/1"
        const val PLAY_ID = "$EPISODE@1000"
    }
}
