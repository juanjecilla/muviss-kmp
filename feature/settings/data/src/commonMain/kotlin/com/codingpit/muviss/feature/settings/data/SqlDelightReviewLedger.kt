package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.review.ReviewLedger
import com.codingpit.muviss.core.common.review.ReviewRecord
import com.codingpit.muviss.core.database.AppSettingsQueries
import kotlinx.coroutines.withContext

/**
 * [ReviewLedger] over the four `appSettings` review columns (`25.sqm`, #410). Per device, never
 * synced and never exported. Every write creates the row through `ensureRow`, the same as the other
 * settings writes, so a counter made after Delete all data starts a fresh row rather than silently
 * dropping the tick. A fresh row's Next Pick result mode is NULL and resolves on read (#853).
 */
class SqlDelightReviewLedger(
    private val queries: AppSettingsQueries,
    private val dispatchers: AppDispatchers,
) : ReviewLedger {

    private suspend fun ensureRow() = withContext(dispatchers.io) { queries.ensureRow() }

    override suspend fun read(): ReviewRecord = withContext(dispatchers.io) {
        val row = queries.selectReviewLedger().awaitAsOneOrNull()
            ?: return@withContext ReviewRecord(installedAtEpochMs = null, tickCount = 0, lastAskedAtEpochMs = null, lastAskedVersion = null)
        ReviewRecord(
            installedAtEpochMs = row.reviewInstalledAtEpochMs,
            tickCount = row.reviewTickCount.toInt(),
            lastAskedAtEpochMs = row.reviewLastAskedAtEpochMs,
            lastAskedVersion = row.reviewLastAskedVersion,
        )
    }

    override suspend fun stampInstalledIfUnknown(nowEpochMs: Long) = withContext(dispatchers.io) {
        ensureRow()
        queries.stampReviewInstalledIfUnknown(nowEpochMs)
        Unit
    }

    override suspend fun countTick() = withContext(dispatchers.io) {
        ensureRow()
        queries.incrementReviewTickCount()
        Unit
    }

    override suspend fun recordAsk(nowEpochMs: Long, version: String) = withContext(dispatchers.io) {
        ensureRow()
        queries.recordReviewAsked(nowEpochMs, version)
        Unit
    }
}
