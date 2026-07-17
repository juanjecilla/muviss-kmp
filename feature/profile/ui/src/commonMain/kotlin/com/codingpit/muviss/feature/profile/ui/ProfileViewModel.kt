package com.codingpit.muviss.feature.profile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.feature.profile.domain.LocalProfile
import com.codingpit.muviss.feature.profile.domain.ObserveProfileStatsUseCase
import com.codingpit.muviss.feature.profile.domain.ObserveProfileUseCase
import com.codingpit.muviss.feature.profile.domain.ProfileActions
import com.codingpit.muviss.feature.profile.domain.ProfileStats
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import com.codingpit.muviss.feature.profile.domain.SyncActions
import com.codingpit.muviss.feature.profile.domain.SyncOutcomeSummary
import com.codingpit.muviss.feature.profile.domain.lastSyncedLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The sync (EPIC 9) slice of [ProfileUiState] — its own data class so the identity/stats slice above stays as readable as it was pre-EPIC 9. */
data class SyncUiState(
    val account: SyncAccountState = SyncAccountState.Unavailable,
    val lastSyncedAtEpochMs: Long? = null,
    val lastSyncedLabel: String = "Never synced",
    val syncing: Boolean = false,
    val isEnteringEmail: Boolean = false,
    val isEnteringCode: Boolean = false,
    val pendingEmail: String? = null,
    /** One-shot: a message to surface in a snackbar (sent-code confirmation, sign-in success, sync outcome, or an error). Cleared by [ProfileViewModel.syncMessageShown]. */
    val message: String? = null,
)

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
            .catch { e -> _state.update { it.copy(loading = false, error = e.message ?: DEFAULT_ERROR) } }
            .onEach { (profile, stats) -> _state.update { it.copy(loading = false, profile = profile, stats = stats, error = null) } }
            .launchIn(viewModelScope)

        combine(syncActions.observeAccount(), syncActions.observeLastSyncedAt()) { account, lastSyncedAt -> account to lastSyncedAt }
            .onEach { (account, lastSyncedAt) ->
                _state.update {
                    it.copy(
                        sync = it.sync.copy(
                            account = account,
                            lastSyncedAtEpochMs = lastSyncedAt,
                            lastSyncedLabel = lastSyncedLabel(lastSyncedAt, clock.nowEpochMs()),
                        ),
                    )
                }
            }
            .launchIn(viewModelScope)
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
        viewModelScope.launch { actions.setDisplayName(trimmed) }
    }

    fun onAvatarSelected(avatarId: String) {
        viewModelScope.launch { actions.setAvatar(avatarId) }
    }

    /** Opens the email step, unless sync isn't configured for this build (no Supabase keys) — surfaces a message instead, the button should really be hidden in that state (see [SyncAccountState.Unavailable]). */
    fun onSignInClicked() {
        if (!syncActions.isAvailable) {
            _state.update { it.copy(sync = it.sync.copy(message = "Sync isn't set up for this build")) }
            return
        }
        _state.update { it.copy(sync = it.sync.copy(isEnteringEmail = true)) }
    }

    fun onSignInEmailDismissed() {
        _state.update { it.copy(sync = it.sync.copy(isEnteringEmail = false)) }
    }

    fun onSignInEmailConfirmed(email: String) {
        val trimmed = email.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(sync = it.sync.copy(syncing = true)) }
            val result = syncActions.requestSignInCode(trimmed)
            _state.update {
                it.copy(
                    sync = it.sync.copy(
                        syncing = false,
                        isEnteringEmail = false,
                        isEnteringCode = result.isSuccess,
                        pendingEmail = trimmed.takeIf { result.isSuccess },
                        message = if (result.isSuccess) "Code sent to $trimmed" else result.exceptionOrNull()?.message ?: "Couldn't send code",
                    ),
                )
            }
        }
    }

    fun onSignInCodeDismissed() {
        _state.update { it.copy(sync = it.sync.copy(isEnteringCode = false, pendingEmail = null)) }
    }

    fun onSignInCodeConfirmed(code: String) {
        val email = _state.value.sync.pendingEmail ?: return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(sync = it.sync.copy(syncing = true)) }
            val result = syncActions.verifySignInCode(email, trimmed)
            _state.update {
                it.copy(
                    sync = it.sync.copy(
                        isEnteringCode = !result.isSuccess,
                        pendingEmail = if (result.isSuccess) null else email,
                        message = if (result.isSuccess) "Signed in" else result.exceptionOrNull()?.message ?: "Invalid code",
                    ),
                )
            }
            if (result.isSuccess) runSyncNow()
        }
    }

    fun onSignOutClicked() {
        viewModelScope.launch { syncActions.signOut() }
    }

    fun onSyncNowClicked() {
        viewModelScope.launch { runSyncNow() }
    }

    fun syncMessageShown() {
        _state.update { it.copy(sync = it.sync.copy(message = null)) }
    }

    private suspend fun runSyncNow() {
        _state.update { it.copy(sync = it.sync.copy(syncing = true)) }
        val outcome = syncActions.syncNow()
        val message = when (outcome) {
            SyncOutcomeSummary.Unavailable, SyncOutcomeSummary.NotSignedIn -> null
            is SyncOutcomeSummary.Success -> "Synced"
            is SyncOutcomeSummary.Failed -> outcome.message
        }
        _state.update { it.copy(sync = it.sync.copy(syncing = false, message = message ?: it.sync.message)) }
    }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong"
    }
}
