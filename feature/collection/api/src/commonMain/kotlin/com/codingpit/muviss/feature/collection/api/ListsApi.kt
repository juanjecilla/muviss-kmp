package com.codingpit.muviss.feature.collection.api

import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow

/**
 * Public contract for user-defined lists (EPIC 17, TV Time parity — e.g.
 * "Marathon 2026"). A sibling contract to [CollectionApi] rather than more
 * methods bolted onto it: lists are orthogonal to library membership (a
 * title can be listed without being saved, saved without being listed, or
 * both), and splitting keeps [DefaultCollectionApi][com.codingpit.muviss.feature.collection.data.DefaultCollectionApi]
 * untouched and each implementation's constructor comfortably under
 * detekt's `LongParameterList` threshold — see
 * [DefaultListsApi][com.codingpit.muviss.feature.collection.data.DefaultListsApi].
 *
 * Peers (search:ui's `DetailScreen` for "Add to list"; this feature's own
 * Lists screens) depend on this module only, same rule as [CollectionApi].
 */
interface ListsApi {
    /** All non-deleted lists, newest-created first, with live entry counts. */
    fun observeLists(): Flow<List<ListSummary>>

    /** [listId]'s contents, newest-added first; entries whose title has no live library snapshot are hidden (see `ListsRepository`'s KDoc for why). */
    fun observeListContents(listId: String): Flow<List<ListItemSummary>>

    /** Ids of every list currently containing [mediaId] — drives Detail's "Add to list" checkmarks. */
    fun observeListMembership(mediaId: MediaId): Flow<Set<String>>

    /** Creates a new list named [name] (blank/whitespace-only throws [IllegalArgumentException]) and returns its id. */
    suspend fun createList(name: String): String

    suspend fun renameList(listId: String, name: String)

    /** Soft-deletes the list and, cascading, every one of its entries. */
    suspend fun deleteList(listId: String)

    /** Adds [mediaId] to [listId]; un-deletes and preserves the original add date if it was previously removed. */
    suspend fun addToList(listId: String, mediaId: MediaId)

    /** Removes [mediaId] from [listId]; a no-op if it wasn't a member. */
    suspend fun removeFromList(listId: String, mediaId: MediaId)
}

/** One list, as rendered by the Lists screen and Detail's "Add to list" sheet. */
data class ListSummary(
    val id: String,
    val name: String,
    val entryCount: Int,
    val createdAtEpochMs: Long,
)

/** One title within a list, as rendered by the list-contents screen. */
data class ListItemSummary(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val addedAtEpochMs: Long,
)
