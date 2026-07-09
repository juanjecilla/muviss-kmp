package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow

/**
 * Domain-owned contract for the saved library. The implementation lives in
 * the data layer over SQLDelight (`CollectionEntry.sq`).
 */
interface CollectionRepository {
    /** All non-deleted entries, newest-saved first; recomputed whenever the table changes. */
    fun observeAll(): Flow<List<CollectionEntry>>

    /** The entry for [mediaId], or null while it is not saved (or has been removed). */
    fun observeEntry(mediaId: MediaId): Flow<CollectionEntry?>

    /**
     * Upserts the denormalized snapshot for [details]. Merges into any
     * existing row: re-saving a removed title un-deletes it, and refreshing a
     * saved title's snapshot preserves its favorite flag and original add
     * date. Used by both "add to library" and snapshot refresh.
     */
    suspend fun upsertSnapshot(details: MediaDetails)

    /** Soft-deletes the entry (the row is kept for a future sync change-log). */
    suspend fun remove(mediaId: MediaId)

    suspend fun setFavorite(mediaId: MediaId, favorite: Boolean)
}
