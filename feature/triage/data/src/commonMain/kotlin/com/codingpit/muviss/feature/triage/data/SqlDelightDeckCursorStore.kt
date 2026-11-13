package com.codingpit.muviss.feature.triage.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.TriageDeckCursorQueries
import com.codingpit.muviss.feature.triage.domain.DeckCursorStore
import com.codingpit.muviss.feature.triage.domain.DeckResume
import com.codingpit.muviss.feature.triage.domain.SavedDeckCursor
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.TriageDeckCursor as TriageDeckCursorRow

/**
 * [DeckCursorStore] over the `triageDeckCursor` table (#722, 27.sqm). Per device and never synced, so it
 * reads and writes the local table directly, like every other per-device row.
 */
class SqlDelightDeckCursorStore(
    private val queries: TriageDeckCursorQueries,
    private val dispatchers: AppDispatchers,
) : DeckCursorStore {

    override suspend fun read(key: String): SavedDeckCursor? = withContext(dispatchers.io) {
        queries.selectByKey(key).awaitAsOneOrNull()?.toDomain()
    }

    override suspend fun write(key: String, saved: SavedDeckCursor) = withContext(dispatchers.io) {
        queries.upsert(
            cursorKey = key,
            moviePage = saved.moviePage.toLong(),
            tvPage = saved.tvPage.toLong(),
            moviesResumePage = saved.moviesResumePage.toLong(),
            tvResumePage = saved.tvResumePage.toLong(),
            savedAtEpochMs = saved.savedAtEpochMs,
        )
        // A position past the TTL can never be restored, so it is removed rather than left to pile up.
        queries.deleteSavedBefore(beforeEpochMs = saved.savedAtEpochMs - DeckResume.TTL_MS)
        Unit
    }

    private fun TriageDeckCursorRow.toDomain(): SavedDeckCursor = SavedDeckCursor(
        moviePage = moviePage.toInt(),
        tvPage = tvPage.toInt(),
        moviesResumePage = moviesResumePage.toInt(),
        tvResumePage = tvResumePage.toInt(),
        savedAtEpochMs = savedAtEpochMs,
    )
}
