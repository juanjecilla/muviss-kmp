@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.profile.ui

import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.profile.domain.AutomaticSyncMode
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
import com.codingpit.muviss.feature.profile.domain.SyncFailureKind
import com.codingpit.muviss.feature.profile.domain.SyncOutcomeSummary
import com.codingpit.muviss.feature.profile.domain.SyncProvider
import com.codingpit.muviss.feature.profile.domain.SyncRepository
import com.codingpit.muviss.feature.profile.domain.SyncStatus
import com.codingpit.muviss.feature.profile.domain.SyncStatusDetail
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MetadataError
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
    var failure: Throwable? = null

    override fun observeProfile(): Flow<LocalProfile> = failure?.let { kotlinx.coroutines.flow.flow { throw it } } ?: flow

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

    override suspend fun setRevisitWillingness(mediaId: MediaId, willing: Boolean?) = error("not used")

    override suspend fun setCoWatchPinned(mediaId: MediaId, pinned: Boolean) = error("not used")
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

private class FakeSyncRepository(
    override val isAvailable: Boolean = true,
    initialAccount: SyncAccountState = SyncAccountState.SignedOut,
    override val isBackgroundAvailable: Boolean = true,
    override val automaticSyncMode: AutomaticSyncMode = AutomaticSyncMode.InBackground,
) : SyncRepository {
    val automaticSync = MutableStateFlow(false)
    val status = MutableStateFlow(SyncStatus())
    var resyncCalls = 0
    var resyncResult: SyncOutcomeSummary = SyncOutcomeSummary.Success(2_000L)

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

    override fun observeAutomaticSync(): Flow<Boolean> = automaticSync

    override suspend fun setAutomaticSync(enabled: Boolean) {
        automaticSync.value = enabled
    }

    override fun observeSyncStatus(): Flow<SyncStatus> = status

    override suspend fun resyncEverything(): SyncOutcomeSummary {
        resyncCalls++
        return resyncResult
    }

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
        ObserveProfileStatsUseCase(collectionApi, progressApi, FakeClock(epochMsAtStartOfDay(0))),
        ProfileActions(SetDisplayNameUseCase(profileRepository), SetAvatarUseCase(profileRepository)),
        SyncActions(syncRepository, ObserveSyncAccountUseCase(syncRepository), ObserveLastSyncedAtUseCase(syncRepository)),
        FakeClock(epochMsAtStartOfDay(0)),
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
        assertEquals("Sync isn't set up for this build", vm.state.value.sync.message.text())
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
        assertEquals("Couldn't start sign-in", vm.state.value.sync.message.text())
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

        assertEquals("code challenge does not match", vm.state.value.sync.message.text())
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

        assertEquals("Synced", vm.state.value.sync.message.text())
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

    // --- EPIC 40: automatic sync ------------------------------------------------

    private val signedIn = SyncAccountState.SignedIn("person@example.com")

    @Test
    fun the_switch_is_not_offered_in_a_build_without_background_sync() = runTest {
        val vm = viewModel(syncRepository = FakeSyncRepository(initialAccount = signedIn, isBackgroundAvailable = false))
        advanceUntilIdle()

        assertEquals(false, vm.state.value.sync.automaticSyncAvailable)
    }

    @Test
    fun the_switch_is_offered_but_only_usable_when_signed_in() = runTest {
        val repository = FakeSyncRepository(initialAccount = SyncAccountState.SignedOut)
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        assertEquals(true, vm.state.value.sync.automaticSyncAvailable)
        assertEquals(false, vm.state.value.sync.automaticSyncEnabled, "signed out: shown, not usable")

        repository.account.value = signedIn
        advanceUntilIdle()
        assertEquals(true, vm.state.value.sync.automaticSyncEnabled)

        repository.account.value = SyncAccountState.Locked("person@example.com")
        advanceUntilIdle()
        assertEquals(false, vm.state.value.sync.automaticSyncEnabled, "not entitled: not usable")
    }

    @Test
    fun the_switch_reflects_the_stored_preference_and_the_platforms_mode() = runTest {
        val repository = FakeSyncRepository(initialAccount = signedIn, automaticSyncMode = AutomaticSyncMode.WhenSystemAllows)
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()
        assertEquals(false, vm.state.value.sync.automaticSync, "off by default")
        assertEquals(AutomaticSyncMode.WhenSystemAllows, vm.state.value.sync.automaticSyncMode)

        repository.automaticSync.value = true
        advanceUntilIdle()
        assertEquals(true, vm.state.value.sync.automaticSync)
    }

    @Test
    fun toggling_the_switch_stores_it() = runTest {
        val repository = FakeSyncRepository(initialAccount = signedIn)
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        vm.onAutomaticSyncToggled(true)
        advanceUntilIdle()
        assertEquals(true, repository.automaticSync.value)

        vm.onAutomaticSyncToggled(false)
        advanceUntilIdle()
        assertEquals(false, repository.automaticSync.value)
    }

    @Test
    fun a_signed_out_toggle_is_ignored() = runTest {
        val repository = FakeSyncRepository(initialAccount = SyncAccountState.SignedOut)
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        vm.onAutomaticSyncToggled(true)
        advanceUntilIdle()

        assertEquals(false, repository.automaticSync.value)
    }

    @Test
    fun the_status_line_follows_the_engine() = runTest {
        val repository = FakeSyncRepository(initialAccount = signedIn)
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        repository.status.value = SyncStatus(lastSyncedAtEpochMs = 0L, pendingChanges = 4)
        advanceUntilIdle()
        assertEquals("Synced just now", vm.state.value.sync.lastSyncedLabel)
        assertEquals(SyncStatusDetail.Waiting(4), vm.state.value.sync.statusDetail)

        repository.status.value = SyncStatus(lastSyncedAtEpochMs = 0L, pendingChanges = 4, lastFailure = SyncFailureKind.Offline)
        advanceUntilIdle()
        assertEquals(SyncStatusDetail.Failed(SyncFailureKind.Offline), vm.state.value.sync.statusDetail)
    }

    @Test
    fun a_failed_sync_says_why_in_words_not_in_exception_text() = runTest {
        val repository = FakeSyncRepository(initialAccount = signedIn).apply {
            syncNowResult = SyncOutcomeSummary.Failed(SyncFailureKind.Server)
        }
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        vm.onSyncNowClicked()
        advanceUntilIdle()

        assertEquals("Sync failed: the sync service had a problem, try again later", vm.state.value.sync.message.text())
    }

    @Test
    fun a_different_account_is_explained_and_nothing_is_offered_to_fix_it() = runTest {
        val repository = FakeSyncRepository(initialAccount = signedIn).apply { syncNowResult = SyncOutcomeSummary.AccountChanged }
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        vm.onSyncNowClicked()
        advanceUntilIdle()

        assertEquals("This device's library belongs to a different account, so nothing was synced.", vm.state.value.sync.message.text())
    }

    @Test
    fun a_dead_session_is_passed_through_as_expired() = runTest {
        val repository = FakeSyncRepository(initialAccount = SyncAccountState.SessionExpired)
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        assertEquals(SyncAccountState.SessionExpired, vm.state.value.sync.account)
    }

    @Test
    fun resync_everything_asks_first_and_does_nothing_until_confirmed() = runTest {
        val repository = FakeSyncRepository(initialAccount = signedIn)
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()

        vm.onResyncEverythingRequested()
        assertEquals(true, vm.state.value.sync.confirmingResync)
        assertEquals(0, repository.resyncCalls)

        vm.onResyncEverythingDismissed()
        assertEquals(false, vm.state.value.sync.confirmingResync)
        assertEquals(0, repository.resyncCalls, "cancelling changes nothing")
    }

    @Test
    fun confirming_resync_runs_it_and_closes_the_dialog() = runTest {
        val repository = FakeSyncRepository(initialAccount = signedIn)
        val vm = viewModel(syncRepository = repository)
        advanceUntilIdle()
        vm.onResyncEverythingRequested()

        vm.onResyncEverythingConfirmed()
        advanceUntilIdle()

        assertEquals(1, repository.resyncCalls)
        assertEquals(false, vm.state.value.sync.confirmingResync)
        assertEquals(false, vm.state.value.sync.syncing)
        assertEquals("Everything resynced", vm.state.value.sync.message.text())
    }

    @Test
    fun retry_after_a_failed_profile_load_resubscribes() = runTest {
        val profiles = FakeProfileRepository().apply { failure = MetadataError.Offline() }
        val vm = viewModel(profileRepository = profiles)
        advanceUntilIdle()
        assertEquals(MetadataError.Offline().userMessage, vm.state.value.error.text())

        profiles.failure = null
        vm.retry()
        advanceUntilIdle()

        assertEquals(null, vm.state.value.error)
        assertEquals(false, vm.state.value.loading)
    }
}
