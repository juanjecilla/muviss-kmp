@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.profile.ui

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.profile.domain.LocalProfile
import com.codingpit.muviss.feature.profile.domain.ObserveLastSyncedAtUseCase
import com.codingpit.muviss.feature.profile.domain.ObserveProfileStatsUseCase
import com.codingpit.muviss.feature.profile.domain.ObserveProfileUseCase
import com.codingpit.muviss.feature.profile.domain.ObserveSyncAccountUseCase
import com.codingpit.muviss.feature.profile.domain.ProfileActions
import com.codingpit.muviss.feature.profile.domain.ProfileRepository
import com.codingpit.muviss.feature.profile.domain.SetAvatarUseCase
import com.codingpit.muviss.feature.profile.domain.SetDisplayNameUseCase
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import com.codingpit.muviss.feature.profile.domain.SyncActions
import com.codingpit.muviss.feature.profile.domain.SyncOutcomeSummary
import com.codingpit.muviss.feature.profile.domain.SyncProvider
import com.codingpit.muviss.feature.profile.domain.SyncRepository
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
    override suspend fun setRating(mediaId: MediaId, rating: Int?) = error("not used")
    override suspend fun setNote(mediaId: MediaId, note: String?) = error("not used")
    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = error("not used")
}

private class FakeProgressApi : ProgressApi {
    val activityDays = MutableStateFlow<Set<Long>>(emptySet())
    val rewatchCounts = MutableStateFlow<Map<MediaId, Int>>(emptyMap())
    val rewatchTimestamps = MutableStateFlow<List<Long>>(emptyList())

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = error("not used")

    // Watch-next (EPIC 22) — this fake's subject never asks for it.
    override fun observeWatchNext(): Flow<List<WatchNextItem>> = flowOf(emptyList())
    override suspend fun refreshWatchNextCatalogs() = Unit

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = activityDays
    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) = error("not used")
    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = flowOf(emptyMap())
    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = flowOf(emptyList())
    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = rewatchCounts
    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = rewatchTimestamps
    override suspend fun recordPlay(episodeId: EpisodeId) = Unit
    override suspend fun removeLatestPlay(episodeId: EpisodeId) = Unit
    override suspend fun clearPlays(episodeId: EpisodeId) = Unit
    override suspend fun markSeasonAiredSeen(season: Season, todayEpochDay: Long): List<EpisodeId> = emptyList()
    override suspend fun markShowAiredSeen(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> = emptyList()
    override suspend fun unmarkSeason(season: Season) = Unit
    override suspend fun unmarkShow(seasons: List<Season>) = Unit
    override suspend fun undoBulkMark(episodeIds: List<EpisodeId>) = Unit
    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = error("not used")

    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) = error("not used")

    override suspend fun clearProgress(mediaId: MediaId) = error("not used")
    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = error("not used")
}

private class FixedClock(private val epochDay: Long) : AppClock {
    override fun nowEpochMs(): Long = epochDay * MILLIS_PER_DAY

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

private class FakeSyncRepository(
    override val isAvailable: Boolean = true,
    initialAccount: SyncAccountState = SyncAccountState.SignedOut,
) : SyncRepository {
    val account = MutableStateFlow(initialAccount)
    val lastSyncedAt = MutableStateFlow<Long?>(null)
    var requestedProvider: SyncProvider? = null
    var completedCode: String? = null
    var beginSignInResult: Result<String> = Result.success(AUTHORIZE_URL)
    var completeSignInResult: Result<Unit> = Result.success(Unit)
    var syncNowResult: SyncOutcomeSummary = SyncOutcomeSummary.Success(1_000L)
    var signOutCalled = false
    val signInFailure = MutableStateFlow<String?>(null)
    var failureShownCount = 0

    override fun observeAccount(): Flow<SyncAccountState> = account

    override fun observeLastSyncedAt(): Flow<Long?> = lastSyncedAt

    override suspend fun beginSignIn(provider: SyncProvider): Result<String> {
        requestedProvider = provider
        return beginSignInResult
    }

    override suspend fun completeSignIn(authCode: String): Result<Unit> {
        completedCode = authCode
        if (completeSignInResult.isSuccess) account.value = SyncAccountState.SignedIn("person@example.com")
        return completeSignInResult
    }

    override fun observeSignInFailure(): Flow<String?> = signInFailure

    override fun signInFailureShown() {
        failureShownCount++
        signInFailure.value = null
    }

    override suspend fun signOut() {
        signOutCalled = true
        account.value = SyncAccountState.SignedOut
    }

    override suspend fun syncNow(): SyncOutcomeSummary = syncNowResult

    companion object {
        const val AUTHORIZE_URL = "https://project.supabase.co/auth/v1/authorize?provider=github"
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
        syncRepository: FakeSyncRepository = FakeSyncRepository(),
    ): ProfileViewModel = ProfileViewModel(
        ObserveProfileUseCase(profileRepository),
        ObserveProfileStatsUseCase(collectionApi, progressApi, FixedClock(epochDay = 0)),
        ProfileActions(SetDisplayNameUseCase(profileRepository), SetAvatarUseCase(profileRepository)),
        SyncActions(syncRepository, ObserveSyncAccountUseCase(syncRepository), ObserveLastSyncedAtUseCase(syncRepository)),
        FixedClock(epochDay = 0),
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
    fun onSignInClicked_hands_the_screen_an_authorize_url_to_open() = runTest {
        val repository = FakeSyncRepository(isAvailable = true)
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        vm.onSignInClicked(SyncProvider.GITHUB)
        advanceUntilIdle()

        assertEquals(SyncProvider.GITHUB, repository.requestedProvider)
        assertEquals(FakeSyncRepository.AUTHORIZE_URL, vm.state.value.sync.pendingAuthUrl)
    }

    @Test
    fun onSignInClicked_surfaces_a_message_instead_when_sync_is_unavailable() = runTest {
        val repository = FakeSyncRepository(isAvailable = false)
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        vm.onSignInClicked(SyncProvider.GITHUB)
        advanceUntilIdle()

        assertNull(vm.state.value.sync.pendingAuthUrl)
        assertNull(repository.requestedProvider, "an unavailable build must not reach the backend at all")
        assertEquals("Sync isn't set up for this build", vm.state.value.sync.message)
    }

    @Test
    fun the_url_is_consumed_once_so_returning_to_the_screen_does_not_reopen_the_browser() = runTest {
        val vm = viewModel(syncRepository = FakeSyncRepository())
        advanceUntilIdle()
        vm.onSignInClicked(SyncProvider.GITHUB)
        advanceUntilIdle()

        vm.authUrlOpened()

        assertNull(vm.state.value.sync.pendingAuthUrl)
    }

    @Test
    fun a_failure_to_start_sign_in_is_reported_and_opens_no_browser() = runTest {
        val repository = FakeSyncRepository().apply {
            beginSignInResult = Result.failure(IllegalStateException("Provider not enabled"))
        }
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        vm.onSignInClicked(SyncProvider.GITHUB)
        advanceUntilIdle()

        assertNull(vm.state.value.sync.pendingAuthUrl)
        assertEquals("Couldn't start sign-in", vm.state.value.sync.message)
    }

    @Test
    fun signing_in_arrives_through_the_account_stream_not_a_return_value() = runTest {
        // The redirect lands in MainActivity, which may be long after this
        // ViewModel is gone — so the only thing that can report success is the
        // account Flow this class already collects.
        val repository = FakeSyncRepository()
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        repository.completeSignIn("auth-code")
        advanceUntilIdle()

        assertEquals(SyncAccountState.SignedIn("person@example.com"), vm.state.value.sync.account)
    }

    @Test
    fun a_sign_in_failure_reaches_the_screen_even_though_it_happened_elsewhere() = runTest {
        // The redirect is redeemed at app scope, so the failure arrives on a
        // stream rather than as any call's return value. Before this existed a
        // broken sign-in was indistinguishable from one never attempted, which
        // is how a cancelled exchange shipped (ADR 0014).
        val repository = FakeSyncRepository()
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        repository.signInFailure.value = "code challenge does not match"
        advanceUntilIdle()

        assertEquals("code challenge does not match", vm.state.value.sync.message)
    }

    @Test
    fun showing_the_failure_clears_it_so_it_does_not_come_back() = runTest {
        val repository = FakeSyncRepository()
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()
        repository.signInFailure.value = "invalid grant"
        advanceUntilIdle()

        vm.syncMessageShown()
        advanceUntilIdle()

        assertNull(vm.state.value.sync.message)
        // Cleared at the source too: the failure is shared state, so leaving it
        // set would re-deliver it to the next collector.
        assertEquals(1, repository.failureShownCount)
        assertNull(repository.signInFailure.value)
    }

    @Test
    fun onSyncNowClicked_surfaces_the_outcome_message() = runTest {
        val repository = FakeSyncRepository(initialAccount = SyncAccountState.SignedIn("person@example.com")).apply {
            syncNowResult = SyncOutcomeSummary.Success(5_000L)
        }
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        vm.onSyncNowClicked()
        advanceUntilIdle()

        assertEquals("Synced", vm.state.value.sync.message)
        assertEquals(false, vm.state.value.sync.syncing)
    }

    @Test
    fun onSignOutClicked_returns_to_signed_out() = runTest {
        val repository = FakeSyncRepository(initialAccount = SyncAccountState.SignedIn("person@example.com"))
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        vm.onSignOutClicked()
        advanceUntilIdle()

        assertTrue(repository.signOutCalled)
        assertEquals(SyncAccountState.SignedOut, vm.state.value.sync.account)
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
