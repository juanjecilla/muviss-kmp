@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeDetailRepo(private val details: MediaDetails) : SearchRepository {
    override suspend fun search(query: String) = Result.success(emptyList<MediaSummary>())
    override suspend fun trending() = Result.success(emptyList<MediaSummary>())
    override suspend fun details(id: MediaId) = Result.success(details)
}

private class FakeCollectionApi : CollectionApi {
    private val membership = MutableStateFlow<CollectionMembership?>(null)
    val added = mutableListOf<MediaId>()
    val removed = mutableListOf<MediaId>()

    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = membership

    override suspend fun add(details: MediaDetails) {
        added += details.summary.id
        membership.value = CollectionMembership(details.summary.id, favorite = false)
    }

    override suspend fun remove(mediaId: MediaId) {
        removed += mediaId
        membership.value = null
    }

    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) {
        membership.value = membership.value?.copy(favorite = favorite)
    }
}

class DetailViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val mediaId = MediaId.tmdbMovie("603")
    private val details = MediaDetails(MediaSummary(mediaId, "The Matrix"))

    private fun viewModel(collectionApi: FakeCollectionApi) = DetailViewModel(
        mediaId,
        MediaDetailUseCase(FakeDetailRepo(details)),
        collectionApi,
    )

    @Test
    fun starts_unsaved_and_unfavorited() = runTest {
        val vm = viewModel(FakeCollectionApi())
        advanceUntilIdle()

        assertFalse(vm.state.value.saved)
        assertFalse(vm.state.value.favorite)
    }

    @Test
    fun toggleSaved_adds_the_loaded_details_when_not_saved() = runTest {
        val api = FakeCollectionApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleSaved()
        advanceUntilIdle()

        assertEquals(listOf(mediaId), api.added)
        assertTrue(vm.state.value.saved)
    }

    @Test
    fun toggleSaved_removes_when_already_saved() = runTest {
        val api = FakeCollectionApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleSaved()
        advanceUntilIdle()
        vm.toggleSaved()
        advanceUntilIdle()

        assertEquals(listOf(mediaId), api.removed)
        assertFalse(vm.state.value.saved)
    }

    @Test
    fun toggleFavorite_flips_the_flag_independent_of_saved_state() = runTest {
        val api = FakeCollectionApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleSaved() // save first so favorite has a membership row to flip
        advanceUntilIdle()
        vm.toggleFavorite()
        advanceUntilIdle()

        assertTrue(vm.state.value.favorite)
    }
}
