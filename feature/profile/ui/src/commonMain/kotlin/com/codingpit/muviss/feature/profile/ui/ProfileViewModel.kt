package com.codingpit.muviss.feature.profile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.feature.profile.domain.LocalProfile
import com.codingpit.muviss.feature.profile.domain.ObserveProfileStatsUseCase
import com.codingpit.muviss.feature.profile.domain.ObserveProfileUseCase
import com.codingpit.muviss.feature.profile.domain.ProfileActions
import com.codingpit.muviss.feature.profile.domain.ProfileStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val loading: Boolean = true,
    val profile: LocalProfile = LocalProfile.DEFAULT,
    val stats: ProfileStats = ProfileStats(),
    val error: String? = null,
    val isEditingName: Boolean = false,
    /** One-shot: non-null while the dormant "Sign in to sync" tap should surface a message; cleared by [ProfileViewModel.comingSoonMessageShown]. */
    val comingSoonMessage: String? = null,
)

/**
 * Drives the Profile screen: the local identity ([ObserveProfileUseCase]) and
 * the derived stats ([ObserveProfileStatsUseCase]) are two independent,
 * continuously-observed streams combined into one [ProfileUiState] — mirrors
 * `SettingsViewModel`'s single-state-flow shape.
 */
class ProfileViewModel(
    observeProfile: ObserveProfileUseCase,
    observeProfileStats: ObserveProfileStatsUseCase,
    private val actions: ProfileActions,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        combine(observeProfile(), observeProfileStats()) { profile, stats -> profile to stats }
            .catch { e -> _state.update { it.copy(loading = false, error = e.message ?: DEFAULT_ERROR) } }
            .onEach { (profile, stats) -> _state.update { it.copy(loading = false, profile = profile, stats = stats, error = null) } }
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

    /** The dormant sync entry point (EPIC 9 wires the real flow); today it only surfaces a "coming soon" message. */
    fun onSignInClicked() {
        _state.update { it.copy(comingSoonMessage = "Sign in — coming soon") }
    }

    fun comingSoonMessageShown() {
        _state.update { it.copy(comingSoonMessage = null) }
    }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong"
    }
}
