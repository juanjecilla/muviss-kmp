@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.feature.collection.api.ListItemSummary
import com.codingpit.muviss.feature.collection.api.ListSummary
import com.codingpit.muviss.feature.collection.api.ListsApi
import com.codingpit.muviss.models.MediaId
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
import kotlin.test.assertTrue

/** Test double for [ListsApi]: [addToList]/[removeFromList]/[createList] mutate the backing flows so [observeLists]/[observeListMembership] reflect them, mirroring how the real Koin-bound implementation behaves. */
private class FakeListsApi(lists: List<ListSummary> = emptyList(), memberIds: Set<String> = emptySet()) : ListsApi {
    private val listsFlow = MutableStateFlow(lists)
    private val membershipFlow = MutableStateFlow(memberIds)
    var nextCreatedId = "new-list"

    override fun observeLists(): Flow<List<ListSummary>> = listsFlow
    override fun observeListContents(listId: String): Flow<List<ListItemSummary>> = error("not used")
    override fun observeListMembership(mediaId: MediaId): Flow<Set<String>> = membershipFlow

    override suspend fun createList(name: String): String {
        listsFlow.value = listsFlow.value + ListSummary(nextCreatedId, name, entryCount = 0, createdAtEpochMs = 0L)
        return nextCreatedId
    }

    override suspend fun renameList(listId: String, name: String) = error("not used")
    override suspend fun deleteList(listId: String) = error("not used")

    override suspend fun addToList(listId: String, mediaId: MediaId) {
        membershipFlow.value = membershipFlow.value + listId
    }

    override suspend fun removeFromList(listId: String, mediaId: MediaId) {
        membershipFlow.value = membershipFlow.value - listId
    }
}

class AddToListViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val mediaId = MediaId.tmdbMovie("603")
    private val marathon = ListSummary("marathon", "Marathon 2026", entryCount = 2, createdAtEpochMs = 0L)

    @Test
    fun loading_flips_off_and_reflects_lists_and_membership() = runTest {
        val vm = AddToListViewModel(mediaId, FakeListsApi(lists = listOf(marathon), memberIds = setOf("marathon")))
        advanceUntilIdle()

        assertEquals(listOf(marathon), vm.state.value.lists)
        assertEquals(setOf("marathon"), vm.state.value.memberListIds)
        assertTrue(!vm.state.value.loading)
    }

    @Test
    fun toggle_adds_when_not_a_member() = runTest {
        val api = FakeListsApi(lists = listOf(marathon))
        val vm = AddToListViewModel(mediaId, api)
        advanceUntilIdle()

        vm.toggle("marathon")
        advanceUntilIdle()

        assertEquals(setOf("marathon"), vm.state.value.memberListIds)
    }

    @Test
    fun toggle_removes_when_already_a_member() = runTest {
        val api = FakeListsApi(lists = listOf(marathon), memberIds = setOf("marathon"))
        val vm = AddToListViewModel(mediaId, api)
        advanceUntilIdle()

        vm.toggle("marathon")
        advanceUntilIdle()

        assertTrue(vm.state.value.memberListIds.isEmpty())
    }

    @Test
    fun createAndAdd_creates_a_list_and_immediately_adds_the_title_to_it() = runTest {
        val api = FakeListsApi()
        api.nextCreatedId = "new-list"
        val vm = AddToListViewModel(mediaId, api)
        advanceUntilIdle()

        vm.createAndAdd("Rewatches")
        advanceUntilIdle()

        assertEquals(listOf("Rewatches"), vm.state.value.lists.map { it.name })
        assertEquals(setOf("new-list"), vm.state.value.memberListIds)
    }
}
