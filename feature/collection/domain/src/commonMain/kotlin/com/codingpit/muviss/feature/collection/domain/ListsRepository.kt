package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow

/**
 * Domain-owned contract for user-defined lists (EPIC 17). Backed by
 * `MediaList.sq`'s `mediaList`/`listEntry` tables; kept in the collection
 * feature — the natural owner of "things the user has organized" — rather
 * than a new vertical slice, per this epic's scope.
 *
 * **Orphaned entries.** [observeListContents] joins each `listEntry` row
 * against `collectionEntry` for its display snapshot (title/poster) — the
 * same denormalized snapshot the library screen renders (see CONTEXT.md
 * "Snapshot"). A row whose title was removed from the library (or was
 * added to a list without ever being saved to it in the first place) has
 * no live snapshot to join against, so it is *hidden*, not deleted: the
 * underlying `listEntry` row — and its original [MediaListItem.addedAtEpochMs]
 * — is preserved, and the title reappears the moment it is (re-)saved to
 * the library. This was chosen over giving `listEntry` its own
 * title/poster copy: a second, list-owned snapshot would drift from the
 * library's and double the surface a future refresh has to keep in sync,
 * for a scenario ("browse a list while a title is temporarily unsaved")
 * that is rare enough to accept as hidden-until-resaved.
 * [MediaList.entryCount] applies this exact same rule, so a list's badge
 * count on the Lists screen always matches what tapping into it shows.
 */
interface ListsRepository {
    /** All non-deleted lists, newest-created first, with live entry counts (see the "orphaned entries" rule above). */
    fun observeLists(): Flow<List<MediaList>>

    /** [listId]'s contents, newest-added first; entries with no live library snapshot are hidden (see the "orphaned entries" rule above). */
    fun observeListContents(listId: String): Flow<List<MediaListItem>>

    /** Ids of every non-deleted list currently containing [mediaId] — drives Detail's "Add to list" checkmarks. */
    fun observeListIdsContaining(mediaId: MediaId): Flow<Set<String>>

    /** Creates a new list named [name] (assumed already trimmed/validated by [CreateListUseCase]) and returns it. */
    suspend fun createList(name: String): MediaList

    suspend fun renameList(listId: String, name: String)

    /** Soft-deletes the list and, cascading, every one of its entries — a deleted list shouldn't leave live orphaned membership rows behind. */
    suspend fun deleteList(listId: String)

    /**
     * Adds [mediaId] to [listId]. Un-deletes and preserves the original
     * add timestamp if [mediaId] was previously removed from [listId] —
     * the same re-add convention [CollectionRepository.upsertSnapshot]
     * uses for the library itself.
     */
    suspend fun addEntry(listId: String, mediaId: MediaId)

    /** Soft-deletes the membership row; a no-op if [mediaId] was never in [listId]. */
    suspend fun removeEntry(listId: String, mediaId: MediaId)
}
