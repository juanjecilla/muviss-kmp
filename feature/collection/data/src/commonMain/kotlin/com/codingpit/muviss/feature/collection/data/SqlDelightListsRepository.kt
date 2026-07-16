package com.codingpit.muviss.feature.collection.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MediaListQueries
import com.codingpit.muviss.feature.collection.domain.ListsRepository
import com.codingpit.muviss.feature.collection.domain.MediaList
import com.codingpit.muviss.feature.collection.domain.MediaListItem
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.random.Random
import com.codingpit.muviss.core.database.SelectAllLists as SelectAllListsRow
import com.codingpit.muviss.core.database.SelectListContents as SelectListContentsRow

/**
 * SQLDelight-backed [ListsRepository] over `MediaList.sq`. See that
 * interface's KDoc for the orphaned-entry semantics [observeListContents]
 * and [MediaList.entryCount] apply.
 *
 * [createList] generates the primary key itself: `<epoch-ms>-<random-int>`,
 * the "simple uuid-ish scheme" this epic calls for rather than
 * `kotlin.uuid.Uuid` (which would be this codebase's first use of an
 * experimental stdlib API for what is, here, just a locally-unique row id
 * — two lists created in the same millisecond still land on distinct ids
 * thanks to the random suffix).
 */
class SqlDelightListsRepository(
    private val queries: MediaListQueries,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : ListsRepository {

    override fun observeLists(): Flow<List<MediaList>> = queries.selectAllLists()
        .asFlow()
        .mapToList(dispatchers.io)
        .map { rows -> rows.map { it.toDomain() } }

    override fun observeListContents(listId: String): Flow<List<MediaListItem>> = queries.selectListContents(listId)
        .asFlow()
        .mapToList(dispatchers.io)
        .map { rows -> rows.map { it.toDomain() } }

    override fun observeListIdsContaining(mediaId: MediaId): Flow<Set<String>> = queries.selectListIdsContainingMedia(mediaId.toString())
        .asFlow()
        .mapToList(dispatchers.io)
        .map { it.toSet() }

    override suspend fun createList(name: String): MediaList = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        val id = newListId(now)
        queries.insertList(id = id, name = name, createdAtEpochMs = now, updatedAtEpochMs = now, isDirty = true, deleted = false)
        MediaList(id = id, name = name, createdAtEpochMs = now, updatedAtEpochMs = now, entryCount = 0)
    }

    override suspend fun renameList(listId: String, name: String) = withContext(dispatchers.io) {
        queries.renameList(name = name, now = clock.nowEpochMs(), id = listId)
        Unit
    }

    /** Cascades into [MediaListQueries.softDeleteEntriesForList] inside one transaction — see [ListsRepository.deleteList]'s KDoc. */
    override suspend fun deleteList(listId: String) = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        queries.transaction {
            queries.softDeleteEntriesForList(listId)
            queries.softDeleteList(now = now, id = listId)
        }
    }

    /** Looks up any existing (possibly soft-deleted) row first so a re-add preserves the original [MediaListItem.addedAtEpochMs] — see [ListsRepository.addEntry]'s KDoc. */
    override suspend fun addEntry(listId: String, mediaId: MediaId) = withContext(dispatchers.io) {
        val id = mediaId.toString()
        val existing = queries.selectEntry(listId, id).executeAsOneOrNull()
        queries.upsertEntry(listId = listId, mediaId = id, addedAtEpochMs = existing?.addedAtEpochMs ?: clock.nowEpochMs())
        Unit
    }

    override suspend fun removeEntry(listId: String, mediaId: MediaId) = withContext(dispatchers.io) {
        queries.removeEntry(listId = listId, mediaId = mediaId.toString())
        Unit
    }

    private fun newListId(nowEpochMs: Long): String = "$nowEpochMs-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun SelectAllListsRow.toDomain() = MediaList(
        id = id,
        name = name,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        entryCount = entryCount.toInt(),
    )

    private fun SelectListContentsRow.toDomain() = MediaListItem(
        mediaId = MediaId.parse(mediaId),
        title = title,
        posterUrl = posterUrl,
        addedAtEpochMs = addedAtEpochMs,
    )
}
