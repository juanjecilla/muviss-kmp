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
internal class FakeListsRepository(initial: List<MediaList> = emptyList(), private val failure: Throwable? = null) : ListsRepository {
    private val flow = MutableStateFlow(initial)
    val deleteCalls = mutableListOf<String>()

    /** Makes [createList] and [renameList] throw (#73). */
    var writeFailure: Throwable? = null

    override fun observeLists(): Flow<List<MediaList>> = failure?.let { kotlinx.coroutines.flow.flow { throw it } } ?: flow
    override fun observeListContents(listId: String): Flow<List<MediaListItem>> = error("not used")
    override fun observeListIdsContaining(mediaId: MediaId): Flow<Set<String>> = error("not used")

    override suspend fun createList(name: String): MediaList {
        writeFailure?.let { throw it }
        val list = MediaList(id = "id-${flow.value.size}", name = name, createdAtEpochMs = 0L, updatedAtEpochMs = 0L)
        flow.value = flow.value + list
        return list
    }

    override suspend fun renameList(listId: String, name: String) {
        writeFailure?.let { throw it }
        flow.value = flow.value.map { if (it.id == listId) it.copy(name = name) else it }
    }

    val restoreCalls = mutableListOf<Pair<String, Long>>()
    private val deleted = mutableMapOf<String, MediaList>()

    /** Makes [deleteList] and [restoreList] throw (#73). */
    var deleteFailure: Throwable? = null

    override suspend fun deleteList(listId: String): Long {
        deleteFailure?.let { throw it }
        deleteCalls += listId
        flow.value.firstOrNull { it.id == listId }?.let { deleted[listId] = it }
        flow.value = flow.value.filterNot { it.id == listId }
        return DELETED_AT
    }

    override suspend fun restoreList(listId: String, deletedAtEpochMs: Long) {
        deleteFailure?.let { throw it }
        restoreCalls += listId to deletedAtEpochMs
        deleted.remove(listId)?.let { flow.value = flow.value + it }
    }

    companion object {
        const val DELETED_AT = 42L
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
    fun a_failed_create_keeps_the_dialog_open_and_says_why() = runTest {
        val repository = FakeListsRepository().apply { writeFailure = IllegalStateException("SQLITE_FULL /data/x.db") }
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.startCreating()
        vm.createList("Marathon 2026")
        advanceUntilIdle()

        assertTrue(vm.state.value.creating, "the dialog must not close as if it worked")
        assertEquals("Couldn't create the list.", vm.state.value.dialogError)
    }

    @Test
    fun a_failed_rename_keeps_the_dialog_open_and_says_why() = runTest {
        val marathon = MediaList("1", "Marathon 2026", 0L, 0L)
        val repository = FakeListsRepository(listOf(marathon)).apply { writeFailure = MetadataError.Offline() }
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.startEditing(marathon)
        vm.renameList("Marathon 2027")
        advanceUntilIdle()

        assertEquals(marathon, vm.state.value.editing)
        assertEquals(MetadataError.Offline().userMessage, vm.state.value.dialogError)
    }

    @Test
    fun a_delete_is_saved_at_once_and_offers_undo() = runTest {
        // #73 review: a delete held back until the snackbar went was lost
        // with the screen or the process. It is written immediately now.
        val marathon = MediaList("1", "Marathon 2026", 0L, 0L)
        val repository = FakeListsRepository(listOf(marathon))
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.deleteList(marathon)
        advanceUntilIdle()

        assertEquals(listOf("1"), repository.deleteCalls)
        assertEquals(emptyList(), vm.state.value.lists)
        assertEquals(DeletedList(marathon, FakeListsRepository.DELETED_AT), vm.state.value.lastDeleted)
    }

    @Test
    fun undo_restores_with_the_stamp_the_delete_wrote() = runTest {
        val marathon = MediaList("1", "Marathon 2026", 0L, 0L)
        val repository = FakeListsRepository(listOf(marathon))
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.deleteList(marathon)
        advanceUntilIdle()

        vm.undoDelete(vm.state.value.lastDeleted!!)
        advanceUntilIdle()

        assertEquals(listOf("1" to FakeListsRepository.DELETED_AT), repository.restoreCalls)
        assertEquals(listOf(marathon), vm.state.value.lists)
        assertNull(vm.state.value.lastDeleted)
    }

    @Test
    fun a_failed_delete_says_so_and_offers_no_undo() = runTest {
        val marathon = MediaList("1", "Marathon 2026", 0L, 0L)
        val repository = FakeListsRepository(listOf(marathon)).apply { deleteFailure = IllegalStateException("SQLITE_BUSY") }
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.deleteList(marathon)
        advanceUntilIdle()

        assertEquals("Couldn't delete the list.", vm.state.value.message)
        assertNull(vm.state.value.lastDeleted)
        assertEquals(listOf(marathon), vm.state.value.lists)
    }

    @Test
    fun dismissing_an_older_undo_does_not_clear_a_newer_one() = runTest {
        val a = MediaList("1", "A", 0L, 0L)
        val b = MediaList("2", "B", 0L, 0L)
        val vm = viewModel(FakeListsRepository(listOf(a, b)))
        advanceUntilIdle()
        vm.deleteList(a)
        advanceUntilIdle()
        val first = vm.state.value.lastDeleted!!
        vm.deleteList(b)
        advanceUntilIdle()

        vm.deleteUndoDismissed(first)

        assertEquals(b, vm.state.value.lastDeleted?.list)
    }
}
