package com.codingpit.muviss.feature.collection.data

import com.codingpit.muviss.feature.collection.api.ListItemSummary
import com.codingpit.muviss.feature.collection.api.ListSummary
import com.codingpit.muviss.feature.collection.api.ListsApi
import com.codingpit.muviss.feature.collection.domain.ListsUseCases
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Bridges the collection feature's [ListsUseCases] to its public [ListsApi]
 * — the [ListsApi] equivalent of `DefaultCollectionApi`, kept as its own
 * class (rather than folded into `DefaultCollectionApi`) so its
 * constructor doesn't push that class over detekt's `LongParameterList`
 * threshold; see [ListsApi]'s KDoc.
 */
internal class DefaultListsApi(private val listsUseCases: ListsUseCases) : ListsApi {

    override fun observeLists(): Flow<List<ListSummary>> = listsUseCases.observeLists()
        .map { lists -> lists.map { ListSummary(it.id, it.name, it.entryCount, it.createdAtEpochMs) } }

    override fun observeListContents(listId: String): Flow<List<ListItemSummary>> = listsUseCases.observeContents(listId)
        .map { items -> items.map { ListItemSummary(it.mediaId, it.title, it.posterUrl, it.addedAtEpochMs) } }

    override fun observeListMembership(mediaId: MediaId): Flow<Set<String>> = listsUseCases.observeMembership(mediaId)

    override suspend fun createList(name: String): String = listsUseCases.create(name).id

    override suspend fun renameList(listId: String, name: String) = listsUseCases.rename(listId, name)

    override suspend fun deleteList(listId: String) {
        listsUseCases.delete(listId)
    }

    override suspend fun addToList(listId: String, mediaId: MediaId) = listsUseCases.addEntry(listId, mediaId)

    override suspend fun removeFromList(listId: String, mediaId: MediaId) = listsUseCases.removeEntry(listId, mediaId)
}
