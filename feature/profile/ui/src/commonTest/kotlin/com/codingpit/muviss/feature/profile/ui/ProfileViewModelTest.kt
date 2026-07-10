@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.profile.ui

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.profile.domain.LocalProfile
import com.codingpit.muviss.feature.profile.domain.ObserveProfileStatsUseCase
import com.codingpit.muviss.feature.profile.domain.ObserveProfileUseCase
import com.codingpit.muviss.feature.profile.domain.ProfileActions
import com.codingpit.muviss.feature.profile.domain.ProfileRepository
import com.codingpit.muviss.feature.profile.domain.SetAvatarUseCase
import com.codingpit.muviss.feature.profile.domain.SetDisplayNameUseCase
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
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

private class FakeProfileRepository(initial: LocalProfile = LocalProfile.DEFAULT) : ProfileRepository {
    val flow = MutableStateFlow(initial)

    override fun observeProfile(): Flow<LocalProfile> = flow

    override suspend fun setDisplayName(displayName: String) {
        flow.value = flow.value.copy(displayName = displayName)
    }

    override suspend fun setAvatar(avatarId: String) {
        flow.value = flow.value.copy(avatarId = avatarId)
    }
}

private class FakeCollectionApi : CollectionApi {
    val summaries = MutableStateFlow<List<CollectionSummary>>(emptyList())

    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = error("not used")
    override fun observeSummaries(): Flow<List<CollectionSummary>> = summaries
    override suspend fun add(details: MediaDetails) = error("not used")
    override suspend fun remove(mediaId: MediaId) = error("not used")
    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = error("not used")
    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = error("not used")
    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = error("not used")
}

private class FakeProgressApi : ProgressApi {
    val activityDays = MutableStateFlow<Set<Long>>(emptySet())

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = error("not used")
    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = activityDays
    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) = error("not used")
    override suspend fun markSeasonSeen(season: Season) = error("not used")
    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = error("not used")
    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = error("not used")
}

private class FixedClock(private val epochDay: Long) : AppClock {
    override fun nowEpochMs(): Long = epochDay * MILLIS_PER_DAY

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

class ProfileViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        profileRepository: FakeProfileRepository = FakeProfileRepository(),
        collectionApi: FakeCollectionApi = FakeCollectionApi(),
        progressApi: FakeProgressApi = FakeProgressApi(),
    ): ProfileViewModel = ProfileViewModel(
        ObserveProfileUseCase(profileRepository),
        ObserveProfileStatsUseCase(collectionApi, progressApi, FixedClock(epochDay = 0)),
        ProfileActions(SetDisplayNameUseCase(profileRepository), SetAvatarUseCase(profileRepository)),
    )

    @Test
    fun loads_the_default_profile_and_empty_stats() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(false, vm.state.value.loading)
        assertEquals("You", vm.state.value.profile.displayName)
        assertTrue(vm.state.value.stats.isEmpty)
    }

    @Test
    fun onDisplayNameConfirmed_trims_persists_and_closes_the_dialog() = runTest {
        val repository = FakeProfileRepository()
        val vm = viewModel(profileRepository = repository)
        advanceUntilIdle()

        vm.onEditNameRequested()
        assertTrue(vm.state.value.isEditingName)

        vm.onDisplayNameConfirmed("  Juanje  ")
        advanceUntilIdle()

        assertEquals(false, vm.state.value.isEditingName)
        assertEquals("Juanje", vm.state.value.profile.displayName)
        assertEquals("Juanje", repository.flow.value.displayName)
    }

    @Test
    fun onDisplayNameConfirmed_ignores_blank_input() = runTest {
        val repository = FakeProfileRepository()
        val vm = viewModel(profileRepository = repository)
        advanceUntilIdle()

        vm.onDisplayNameConfirmed("   ")
        advanceUntilIdle()

        assertEquals("You", repository.flow.value.displayName)
    }

    @Test
    fun onAvatarSelected_persists_the_chosen_preset() = runTest {
        val repository = FakeProfileRepository()
        val vm = viewModel(profileRepository = repository)
        advanceUntilIdle()

        vm.onAvatarSelected("teal")
        advanceUntilIdle()

        assertEquals("teal", vm.state.value.profile.avatarId)
    }

    @Test
    fun onSignInClicked_surfaces_a_one_shot_coming_soon_message() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSignInClicked()
        assertEquals("Sign in — coming soon", vm.state.value.comingSoonMessage)

        vm.comingSoonMessageShown()
        assertNull(vm.state.value.comingSoonMessage)
    }

    @Test
    fun stats_react_to_the_saved_library_changing() = runTest {
        val collectionApi = FakeCollectionApi()
        val vm = viewModel(collectionApi = collectionApi)
        advanceUntilIdle()

        collectionApi.summaries.value = listOf(
            CollectionSummary(
                mediaId = MediaId.tmdbMovie("603"),
                title = "The Matrix",
                posterUrl = null,
                status = WatchStatus.WATCHED,
                runtimeMinutes = 100,
            ),
        )
        advanceUntilIdle()

        assertEquals(false, vm.state.value.stats.isEmpty)
        assertEquals(1, vm.state.value.stats.moviesWatched)
    }
}
