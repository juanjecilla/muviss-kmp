package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow

/**
 * Every user-defined-list operation (EPIC 17), bundled behind a single
 * constructor dependency ([ListsRepository]) rather than one use-case class
 * per operation. Every consumer of this domain — the collection feature's
 * own Lists screens, [com.codingpit.muviss.feature.collection.data.DefaultListsApi]
 * — needs most or all eight operations at once, so the
 * [CollectionUseCases.kt][CollectionToggles]-style per-operation split
 * would just mean every one of those constructors grows past detekt's
 * `LongParameterList` threshold for no real benefit (unlike
 * [CollectionToggles], which groups four *related* mutators while
 * `CollectionRepository`'s reads and `AddToCollectionUseCase`/
 * `RemoveFromCollectionUseCase` stay independently injectable because
 * different callers genuinely only need one or two of those).
 *
 * [create] and [rename] own the one piece of real business logic here:
 * trimming and rejecting a blank name (the range-validation equivalent of
 * [SetRatingUseCase] for lists).
 */
class ListsUseCases(private val repository: ListsRepository) {

    fun observeLists(): Flow<List<MediaList>> = repository.observeLists()

    fun observeContents(listId: String): Flow<List<MediaListItem>> = repository.observeListContents(listId)

    fun observeMembership(mediaId: MediaId): Flow<Set<String>> = repository.observeListIdsContaining(mediaId)

    suspend fun create(name: String): MediaList = repository.createList(name.requireNonBlank())

    suspend fun rename(listId: String, name: String) = repository.renameList(listId, name.requireNonBlank())

    suspend fun delete(listId: String): Long = repository.deleteList(listId)

    suspend fun restore(listId: String, deletedAtEpochMs: Long) = repository.restoreList(listId, deletedAtEpochMs)

    suspend fun addEntry(listId: String, mediaId: MediaId) = repository.addEntry(listId, mediaId)

    suspend fun removeEntry(listId: String, mediaId: MediaId) = repository.removeEntry(listId, mediaId)

    private fun String.requireNonBlank(): String {
        val trimmed = trim()
        require(trimmed.isNotEmpty()) { "list name must not be blank" }
        return trimmed
    }
}
