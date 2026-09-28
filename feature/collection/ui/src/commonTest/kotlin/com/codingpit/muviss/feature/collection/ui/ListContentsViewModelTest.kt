@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.ui

import com.codingpit.muviss.feature.collection.domain.ListsRepository
import com.codingpit.muviss.feature.collection.domain.ListsUseCases
import com.codingpit.muviss.feature.collection.domain.MediaList
import com.codingpit.muviss.feature.collection.domain.MediaListItem
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Test double for [ListsRepository]: [removeEntry] mutates [contentsByList] so [observeListContents] reflects it. */
private class FakeContentsRepository(initialContents: List<MediaListItem>, private val failure: Throwable? = null) : ListsRepository {
    private val contents = MutableStateFlow(initialContents)
    val removeCalls = mutableListOf<Pair<String, MediaId>>()

    override fun observeLists(): Flow<List<MediaList>> = error("not used")
    override fun observeListContents(listId: String): Flow<List<MediaListItem>> = failure?.let { flow { throw it } } ?: contents
    override fun observeListIdsContaining(mediaId: MediaId): Flow<Set<String>> = error("not used")
    override suspend fun createList(name: String) = error("not used")
    override suspend fun renameList(listId: String, name: String) = error("not used")
    override suspend fun deleteList(listId: String) = error("not used")
    override suspend fun addEntry(listId: String, mediaId: MediaId) = error("not used")

    override suspend fun removeEntry(listId: String, mediaId: MediaId) {
        removeCalls += listId to mediaId
        contents.value = contents.value.filterNot { it.mediaId == mediaId }
    }
}

class ListContentsViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val matrix = MediaListItem(MediaId.tmdbMovie("603"), "The Matrix", null, 1_000L)

    @Test
    fun loading_flips_off_and_reflects_the_lists_contents() = runTest {
        val repository = FakeContentsRepository(listOf(matrix))
        val vm = ListContentsViewModel("list-1", ListsUseCases(repository))
        advanceUntilIdle()

        assertEquals(listOf(matrix), vm.state.value.items)
        assertTrue(!vm.state.value.loading)
    }

    @Test
    fun a_metadata_error_shows_its_mapped_copy() = runTest {
        val repository = FakeContentsRepository(emptyList(), failure = MetadataError.RateLimited(retryAfterSeconds = 12))
        val vm = ListContentsViewModel("list-1", ListsUseCases(repository))
        advanceUntilIdle()

        assertEquals(MetadataError.RateLimited().userMessage, vm.state.value.error)
    }

    @Test
    fun a_raw_exception_never_reaches_the_screen() = runTest {
        val leaky = IllegalStateException("Unable to resolve host api.themoviedb.org?api_key=SECRET")
        val repository = FakeContentsRepository(emptyList(), failure = leaky)
        val vm = ListContentsViewModel("list-1", ListsUseCases(repository))
        advanceUntilIdle()

        assertEquals("Something went wrong", vm.state.value.error)
    }

    @Test
    fun removeEntry_delegates_to_the_repository_with_this_screens_listId() = runTest {
        val repository = FakeContentsRepository(listOf(matrix))
        val vm = ListContentsViewModel("list-1", ListsUseCases(repository))
        advanceUntilIdle()

        vm.removeEntry(matrix.mediaId)
        advanceUntilIdle()

        assertEquals(listOf("list-1" to matrix.mediaId), repository.removeCalls)
        assertTrue(vm.state.value.items.isEmpty())
    }
}
