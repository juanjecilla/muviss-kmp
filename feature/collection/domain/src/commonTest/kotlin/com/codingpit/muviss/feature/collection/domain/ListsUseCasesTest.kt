package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Test double for [ListsRepository]: [createList]/[renameList] just record the (already-processed) name they were called with. */
private class RecordingListsRepository : ListsRepository {
    val createCalls = mutableListOf<String>()
    val renameCalls = mutableListOf<Pair<String, String>>()

    override fun observeLists(): Flow<List<MediaList>> = flowOf(emptyList())
    override fun observeListContents(listId: String): Flow<List<MediaListItem>> = error("not used")
    override fun observeListIdsContaining(mediaId: MediaId): Flow<Set<String>> = error("not used")

    override suspend fun createList(name: String): MediaList {
        createCalls += name
        return MediaList(id = "id", name = name, createdAtEpochMs = 0L, updatedAtEpochMs = 0L)
    }

    override suspend fun renameList(listId: String, name: String) {
        renameCalls += listId to name
    }

    override suspend fun deleteList(listId: String) = error("not used")
    override suspend fun addEntry(listId: String, mediaId: MediaId) = error("not used")
    override suspend fun removeEntry(listId: String, mediaId: MediaId) = error("not used")
}

/**
 * [ListsUseCases.create]/[ListsUseCases.rename] are the only places a list
 * name is trimmed and validated non-blank — the repository/database column
 * accept any text — so those two are the pieces of this bundle worth unit
 * testing in isolation (the plain pass-throughs are exercised indirectly
 * via the ViewModel and repository tests instead).
 */
class ListsUseCasesTest {

    @Test
    fun create_trims_surrounding_whitespace() = runTest {
        val repository = RecordingListsRepository()
        val useCases = ListsUseCases(repository)

        useCases.create("  Marathon 2026  ")

        assertEquals(listOf("Marathon 2026"), repository.createCalls)
    }

    @Test
    fun create_rejects_a_blank_name() = runTest {
        val useCases = ListsUseCases(RecordingListsRepository())

        val result = runCatching { useCases.create("   ") }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun rename_trims_and_validates_the_same_way_as_create() = runTest {
        val repository = RecordingListsRepository()
        val useCases = ListsUseCases(repository)

        useCases.rename("list-1", "  Rewatches  ")

        assertEquals(listOf("list-1" to "Rewatches"), repository.renameCalls)
    }

    @Test
    fun rename_rejects_a_blank_name() = runTest {
        val useCases = ListsUseCases(RecordingListsRepository())

        val result = runCatching { useCases.rename("list-1", "") }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
