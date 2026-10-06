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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test double for [ListsRepository]: [createList]/[renameList]/[deleteList] just record their calls and mutate [lists] so [observeLists] reflects them. */
internal class FakeListsRepository(initial: List<MediaList> = emptyList(), var failure: Throwable? = null) : ListsRepository {
    private val flow = MutableStateFlow(initial)
    val deleteCalls = mutableListOf<String>()

    override fun observeLists(): Flow<List<MediaList>> = failure?.let { kotlinx.coroutines.flow.flow { throw it } } ?: flow
    override fun observeListContents(listId: String): Flow<List<MediaListItem>> = error("not used")
    override fun observeListIdsContaining(mediaId: MediaId): Flow<Set<String>> = error("not used")

    override suspend fun createList(name: String): MediaList {
        val list = MediaList(id = "id-${flow.value.size}", name = name, createdAtEpochMs = 0L, updatedAtEpochMs = 0L)
        flow.value = flow.value + list
        return list
    }

    override suspend fun renameList(listId: String, name: String) {
        flow.value = flow.value.map { if (it.id == listId) it.copy(name = name) else it }
    }

    override suspend fun deleteList(listId: String) {
        deleteCalls += listId
        flow.value = flow.value.filterNot { it.id == listId }
    }

    override suspend fun addEntry(listId: String, mediaId: MediaId) = error("not used")
    override suspend fun removeEntry(listId: String, mediaId: MediaId) = error("not used")
}

class ListsViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: FakeListsRepository) = ListsViewModel(ListsUseCases(repository))

    @Test
    fun loading_flips_off_and_reflects_the_observed_lists() = runTest {
        val marathon = MediaList("1", "Marathon 2026", 0L, 0L)
        val vm = viewModel(FakeListsRepository(listOf(marathon)))
        advanceUntilIdle()

        assertEquals(listOf(marathon), vm.state.value.lists)
        assertTrue(!vm.state.value.loading)
    }

    @Test
    fun a_metadata_error_shows_its_mapped_copy() = runTest {
        val vm = viewModel(FakeListsRepository(failure = MetadataError.RateLimited(retryAfterSeconds = 12)))
        advanceUntilIdle()

        assertEquals(MetadataError.RateLimited().userMessage, vm.state.value.error)
    }

    @Test
    fun a_raw_exception_never_reaches_the_screen() = runTest {
        val leaky = IllegalStateException("Unable to resolve host api.themoviedb.org?api_key=SECRET")
        val vm = viewModel(FakeListsRepository(failure = leaky))
        advanceUntilIdle()

        assertEquals("Something went wrong", vm.state.value.error)
    }

    @Test
    fun createList_trims_the_name_via_ListsUseCases_and_closes_the_dialog() = runTest {
        val repository = FakeListsRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.startCreating()
        assertTrue(vm.state.value.creating)

        vm.createList("  Marathon 2026  ")
        advanceUntilIdle()

        assertEquals(listOf("Marathon 2026"), vm.state.value.lists.map { it.name })
        assertTrue(!vm.state.value.creating)
    }

    @Test
    fun createList_with_a_blank_name_leaves_the_library_untouched() = runTest {
        val repository = FakeListsRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.createList("   ")
        advanceUntilIdle()

        assertTrue(vm.state.value.lists.isEmpty())
    }

    @Test
    fun startEditing_then_renameList_updates_the_name_and_clears_editing() = runTest {
        val marathon = MediaList("1", "Marathon 2026", 0L, 0L)
        val repository = FakeListsRepository(listOf(marathon))
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.startEditing(marathon)
        assertEquals(marathon, vm.state.value.editing)

        vm.renameList("Rewatches")
        advanceUntilIdle()

        assertEquals(listOf("Rewatches"), vm.state.value.lists.map { it.name })
        assertNull(vm.state.value.editing)
    }

    @Test
    fun cancelEditing_clears_without_renaming() = runTest {
        val marathon = MediaList("1", "Marathon 2026", 0L, 0L)
        val vm = viewModel(FakeListsRepository(listOf(marathon)))
        advanceUntilIdle()

        vm.startEditing(marathon)
        vm.cancelEditing()

        assertNull(vm.state.value.editing)
        assertEquals("Marathon 2026", vm.state.value.lists.single().name)
    }

    @Test
    fun deleteList_delegates_to_the_repository() = runTest {
        val marathon = MediaList("1", "Marathon 2026", 0L, 0L)
        val repository = FakeListsRepository(listOf(marathon))
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.deleteList(marathon)
        advanceUntilIdle()

        assertEquals(listOf("1"), repository.deleteCalls)
        assertTrue(vm.state.value.lists.isEmpty())
    }

    @Test
    fun retry_after_a_failed_load_resubscribes_and_shows_the_lists() = runTest {
        val repository = FakeListsRepository(listOf(MediaList("1", "Marathon 2026", 0L, 0L)), failure = MetadataError.Offline())
        val vm = viewModel(repository)
        advanceUntilIdle()
        assertEquals(MetadataError.Offline().userMessage, vm.state.value.error)

        repository.failure = null
        vm.retry()
        advanceUntilIdle()

        assertNull(vm.state.value.error)
        assertEquals(listOf("Marathon 2026"), vm.state.value.lists.map { it.name })
    }
}
