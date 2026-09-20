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
     * "Add to library": saves the denormalized snapshot for [details] as a user
     * action. Merges into any existing row — re-saving a removed title
     * un-deletes it and keeps its favorite flag, rating, note and original add
     * date. A title that is already in the library is left as it was apart from
     * a snapshot refresh, since saving it again says nothing new.
     *
     * A new or revived entry is a synced write. To keep a saved title's
     * provider data current, use [refreshSnapshot].
     */
    suspend fun upsertSnapshot(details: MediaDetails)

    /**
     * Brings a saved title's provider data (poster, title, episode counts,
     * production status...) up to date — and nothing else. **Not a synced
     * write**: it does not bump the row's timestamp, mark it dirty or touch a
     * user field, and it does nothing to a title that is not currently in the
     * library, so a device that merely looked at a title can neither beat
     * another device's newer edit nor un-delete something removed elsewhere.
     * Episode counts are floored at what has already been ticked.
     */
    suspend fun refreshSnapshot(details: MediaDetails)

    /** Soft-deletes the entry (the row is kept for a future sync change-log). */
    suspend fun remove(mediaId: MediaId)

    suspend fun setFavorite(mediaId: MediaId, favorite: Boolean)

    /** Per-show opt-out from EPIC 5's new-episode notifications, independent of the global toggle (see [CollectionEntry.notificationsMuted]). */
    suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean)

    /** Sets or clears (via null) the personal 1-10 rating (EPIC 15). Range validation lives in [SetRatingUseCase], not here. */
    suspend fun setRating(mediaId: MediaId, rating: Int?)

    /** Sets or clears (via null) the personal free-text note (EPIC 15). */
    suspend fun setNote(mediaId: MediaId, note: String?)
}
