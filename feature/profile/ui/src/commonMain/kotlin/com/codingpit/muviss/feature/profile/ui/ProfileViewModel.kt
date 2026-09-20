package com.codingpit.muviss.feature.profile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.feature.profile.domain.AutomaticSyncMode
import com.codingpit.muviss.feature.profile.domain.LocalProfile
import com.codingpit.muviss.feature.profile.domain.ObserveProfileStatsUseCase
import com.codingpit.muviss.feature.profile.domain.ObserveProfileUseCase
import com.codingpit.muviss.feature.profile.domain.ProfileActions
import com.codingpit.muviss.feature.profile.domain.ProfileStats
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import com.codingpit.muviss.feature.profile.domain.SyncActions
import com.codingpit.muviss.feature.profile.domain.SyncCopy
import com.codingpit.muviss.feature.profile.domain.SyncOutcomeSummary
import com.codingpit.muviss.feature.profile.domain.SyncProvider
import com.codingpit.muviss.feature.profile.domain.SyncStatus
import com.codingpit.muviss.feature.profile.domain.SyncStatusDetail
import com.codingpit.muviss.feature.profile.domain.lastSyncedLabel
import com.codingpit.muviss.feature.profile.domain.syncStatusDetail
import com.codingpit.muviss.models.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/** The sync (EPIC 9) slice of [ProfileUiState] — its own data class so the identity/stats slice above stays as readable as it was pre-EPIC 9. */
data class SyncUiState(
    val account: SyncAccountState = SyncAccountState.Unavailable,
    val lastSyncedAtEpochMs: Long? = null,
    val lastSyncedLabel: String = "Never synced",
    val syncing: Boolean = false,
    /** Set when sign-in has produced an authorize URL the screen should open in a browser; cleared by [ProfileViewModel.authUrlOpened]. */
    val pendingAuthUrl: String? = null,
    /**
     * The providers offered on the sign-in row. GitHub only for now — a
     * provider listed here that is not enabled in the Supabase dashboard
     * fails at the authorize URL with a message from GoTrue, so this list
     * tracks what the project actually accepts rather than what the enum can
     * express (ADR 0014).
     */
    val providers: List<SyncProvider> = listOf(SyncProvider.GITHUB),
    /** Where the last attempt stands: last synced, changes waiting, why it failed, account mismatch. */
    val status: SyncStatus = SyncStatus(),
    /** The per-device "sync automatically" switch, off by default. */
    val automaticSync: Boolean = false,
    /** Whether this build ships automatic sync at all. False renders no switch, the same rule as [SyncAccountState.Unavailable]. */
    val automaticSyncAvailable: Boolean = false,
    val automaticSyncMode: AutomaticSyncMode = AutomaticSyncMode.WhileOpen,
    /** The "Resync everything" confirmation dialog is open. */
    val confirmingResync: Boolean = false,
    /** One-shot: a message to surface in a snackbar (sent-code confirmation, sign-in success, sync outcome, or an error). Cleared by [ProfileViewModel.syncMessageShown]. */
    val message: String? = null,
)

/**
 * The switch is shown whenever the build has it, but only usable while signed
 * in and entitled: a paid, account-bound feature offered to someone who cannot
 * use it yet is a reason to sign in, not something to hide.
 */
val SyncUiState.automaticSyncEnabled: Boolean get() = automaticSyncAvailable && account is SyncAccountState.SignedIn

/** The one line worth saying under the "Synced 3m ago" label, if any. */
val SyncUiState.statusDetail: SyncStatusDetail? get() = syncStatusDetail(status)

data class ProfileUiState(
    val loading: Boolean = true,
    val profile: LocalProfile = LocalProfile.DEFAULT,
    val stats: ProfileStats = ProfileStats(),
    val error: String? = null,
    val isEditingName: Boolean = false,
    val sync: SyncUiState = SyncUiState(),
)

/**
 * Drives the Profile screen: the local identity ([ObserveProfileUseCase]),
 * the derived stats ([ObserveProfileStatsUseCase]), and — since EPIC 9 — the
 * sync account state ([SyncActions.observeAccount]/[SyncActions.observeLastSyncedAt])
 * are three independent, continuously-observed streams combined into one
 * [ProfileUiState].
 *
 * Auto-sync on app foreground is deliberately *not* triggered from here:
 * this ViewModel only exists while the Profile screen is on the back stack,
 * but "sync when the app comes back to the foreground" needs to work no
 * matter which screen is visible — see `MuvissApp.kt`'s own
 * `LifecycleEventEffect`, which calls `SyncEngine.syncNow()` directly.
 */
class ProfileViewModel(
    observeProfile: ObserveProfileUseCase,
    observeProfileStats: ObserveProfileStatsUseCase,
    private val actions: ProfileActions,
    private val syncActions: SyncActions,
    private val clock: AppClock,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        combine(observeProfile(), observeProfileStats()) { profile, stats -> profile to stats }
            .catch { e -> _state.update { it.copy(loading = false, error = e.toUserMessage(DEFAULT_ERROR)) } }
            .onEach { (profile, stats) -> _state.update { it.copy(loading = false, profile = profile, stats = stats, error = null) } }
            .launchInReporting(viewModelScope)

        combine(syncActions.observeAccount(), syncActions.observeSyncStatus(), syncActions.observeAutomaticSync()) { account, status, automatic ->
            Triple(account, status, automatic)
        }
            .onEach { (account, status, automatic) ->
                _state.update {
                    it.copy(
                        sync = it.sync.copy(
                            account = account,
                            status = status,
                            lastSyncedAtEpochMs = status.lastSyncedAtEpochMs,
                            lastSyncedLabel = lastSyncedLabel(status.lastSyncedAtEpochMs, clock.nowEpochMs()),
                            automaticSync = automatic,
                            automaticSyncAvailable = syncActions.isBackgroundAvailable,
                            automaticSyncMode = syncActions.automaticSyncMode,
                        ),
                    )
                }
            }
            .launchInReporting(viewModelScope)

        // Sign-in can fail while this screen does not exist — the redirect is
        // redeemed at app scope (ADR 0014) — so the reason arrives here as a
        // stream rather than as a call's return value.
        syncActions.observeSignInFailure()
            .onEach { failure -> if (failure != null) _state.update { it.copy(sync = it.sync.copy(message = failure)) } }
            .launchInReporting(viewModelScope)
    }

    fun onEditNameRequested() {
        _state.update { it.copy(isEditingName = true) }
    }

    fun onEditNameDismissed() {
        _state.update { it.copy(isEditingName = false) }
    }

    /** Ignores a blank name (the dialog's own confirm button is disabled for blank input, this is the defensive second line). */
    fun onDisplayNameConfirmed(name: String) {
        val trimmed = name.trim()
        _state.update { it.copy(isEditingName = false) }
        if (trimmed.isEmpty()) return
        viewModelScope.launchReporting { actions.setDisplayName(trimmed) }
    }

    fun onAvatarSelected(avatarId: String) {
        viewModelScope.launchReporting { actions.setAvatar(avatarId) }
    }

    /**
     * Starts OAuth for [provider], unless sync isn't in this build — then it
     * surfaces a message instead, though the button should already be hidden
     * in that state (see [SyncAccountState.Unavailable]).
     *
     * Completion does not come back here. The user leaves for a browser and
     * returns as a redirect into `MainActivity`, which may well be after this
     * ViewModel has been disposed; the signed-in state arrives instead
     * through [SyncActions.observeAccount], which this class already
     * collects. That is why there is no `onSignInCompleted`.
     */
    fun onSignInClicked(provider: SyncProvider) {
        if (!syncActions.isAvailable) {
            _state.update { it.copy(sync = it.sync.copy(message = "Sync isn't set up for this build")) }
            return
        }
        viewModelScope.launchReporting {
            _state.update { it.copy(sync = it.sync.copy(syncing = true)) }
            val result = syncActions.beginSignIn(provider)
            _state.update {
                it.copy(
                    sync = it.sync.copy(
                        syncing = false,
                        pendingAuthUrl = result.getOrNull(),
                        message = result.exceptionOrNull()?.let { e -> e.toUserMessage("Couldn't start sign-in") },
                    ),
                )
            }
        }
    }

    /** Called once the screen has handed [SyncUiState.pendingAuthUrl] to a browser, so returning to the app doesn't reopen it. */
    fun authUrlOpened() {
        _state.update { it.copy(sync = it.sync.copy(pendingAuthUrl = null)) }
    }

    fun onSignOutClicked() {
        viewModelScope.launchReporting { syncActions.signOut() }
    }

    fun onSyncNowClicked() {
        viewModelScope.launchReporting { runSyncNow() }
    }

    /** Ignored unless the switch is usable, so a stale click cannot store a preference the screen would not have offered. */
    fun onAutomaticSyncToggled(enabled: Boolean) {
        if (!_state.value.sync.automaticSyncEnabled) return
        viewModelScope.launch { syncActions.setAutomaticSync(enabled) }
    }

    fun onResyncEverythingRequested() {
        _state.update { it.copy(sync = it.sync.copy(confirmingResync = true)) }
    }

    fun onResyncEverythingDismissed() {
        _state.update { it.copy(sync = it.sync.copy(confirmingResync = false)) }
    }

    fun onResyncEverythingConfirmed() {
        _state.update { it.copy(sync = it.sync.copy(confirmingResync = false, syncing = true)) }
        viewModelScope.launch {
            val message = messageFor(syncActions.resyncEverything(), success = "Everything resynced")
            _state.update { it.copy(sync = it.sync.copy(syncing = false, message = message ?: it.sync.message)) }
        }
    }

    fun syncMessageShown() {
        _state.update { it.copy(sync = it.sync.copy(message = null)) }
        // Also clears the shared sign-in failure, or it would be re-emitted to
        // the next collector and the message would come back.
        syncActions.signInFailureShown()
    }

    private suspend fun runSyncNow() {
        _state.update { it.copy(sync = it.sync.copy(syncing = true)) }
        val message = messageFor(syncActions.syncNow(), success = "Synced")
        _state.update { it.copy(sync = it.sync.copy(syncing = false, message = message ?: it.sync.message)) }
    }

    private fun messageFor(outcome: SyncOutcomeSummary, success: String): String? = when (outcome) {
        SyncOutcomeSummary.Unavailable, SyncOutcomeSummary.NotSignedIn -> null

        // Worth a message, unlike the two above: those states have no
        // visible sync button to have been pressed, whereas an entitlement
        // can lapse while the screen is open and leave a stale one there.
        SyncOutcomeSummary.NotEntitled -> "Sync is a paid feature"

        is SyncOutcomeSummary.Success -> success

        is SyncOutcomeSummary.Failed -> "Sync failed: ${SyncCopy.failure(outcome.kind)}"

        SyncOutcomeSummary.AccountChanged -> "This device's library belongs to a different account, so nothing was synced."
    }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong"
    }
}
