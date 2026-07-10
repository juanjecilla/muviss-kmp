package com.codingpit.muviss.feature.collection.api

import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.Flow

/**
 * Public contract of the collection feature. Peers (e.g. search's
 * `DetailScreen`, progress's watch-next) depend on this module only — never
 * collection's domain/data/ui — to add, remove, and observe library
 * membership without knowing anything about how the library is stored.
 */
interface CollectionApi {
    /** Emits the current membership for [mediaId], or null while it is not saved. */
    fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?>

    /**
     * Every saved title's derived [WatchStatus] plus the display fields a
     * peer needs to render its own list (e.g. progress's watch-next filters
     * this to [WatchStatus.WATCHING]).
     */
    fun observeSummaries(): Flow<List<CollectionSummary>>

    /** Saves [details] to the library, or refreshes its snapshot if already saved. */
    suspend fun add(details: MediaDetails)

    /** Soft-deletes the entry; a no-op if it was never saved. */
    suspend fun remove(mediaId: MediaId)

    suspend fun setFavorite(mediaId: MediaId, favorite: Boolean)
}

/** The minimal membership info a peer needs to render add/remove + favorite controls. */
data class CollectionMembership(
    val mediaId: MediaId,
    val favorite: Boolean,
)

/** The minimal display + status info a peer needs to render its own view of the library. */
data class CollectionSummary(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val status: WatchStatus,
)
