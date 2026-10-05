package com.codingpit.muviss.feature.cowatch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.feature.cowatch.api.CoWatchApi
import com.codingpit.muviss.feature.cowatch.api.LinkedCompanion
import com.codingpit.muviss.feature.cowatch.api.PoolSettings
import com.codingpit.muviss.feature.cowatch.api.PoolSource
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.Res
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.error_bad_code
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.error_sign_in_to_invite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class CompanionsUiState(
    val loading: Boolean = true,
    val companions: List<LinkedCompanion> = emptyList(),
    val settings: PoolSettings = PoolSettings(PoolSource.NotStarted, includeSeenByDefault = true),
    /** The code this account has minted for someone else to paste, if the user asked for one. */
    val myInviteCode: String? = null,
    val error: UiText? = null,
)

/**
 * Managing Companions: invite, accept, name, unlink, and the pool settings.
 *
 * Errors are carried as a message this screen chose, never as backend exception
 * text — the same rule EPIC 27 applied to network failures, and for the same
 * reason: a raw message can carry a URL or an id into a snackbar and into
 * Sentry.
 */
class CompanionsViewModel(
    private val coWatch: CoWatchApi,
) : ViewModel() {

    private val _state = MutableStateFlow(CompanionsUiState())
    val state: StateFlow<CompanionsUiState> = _state.asStateFlow()

    init {
        coWatch.observeCompanions()
            .onEach { companions -> _state.update { it.copy(loading = false, companions = companions) } }
            .launchInReporting(viewModelScope)
        coWatch.observePoolSettings()
            .onEach { settings -> _state.update { it.copy(settings = settings) } }
            .launchInReporting(viewModelScope)
    }

    fun createInvite() = viewModelScope.launchReporting {
        coWatch.createInvite()
            .onSuccess { code -> _state.update { it.copy(myInviteCode = code, error = null) } }
            .onFailure { _state.update { it.copy(error = UiText.Resource(Res.string.error_sign_in_to_invite)) } }
    }

    fun dismissInvite() = _state.update { it.copy(myInviteCode = null) }

    fun acceptInvite(raw: String) = viewModelScope.launchReporting {
        coWatch.acceptInvite(raw)
            .onSuccess { _state.update { it.copy(error = null) } }
            .onFailure { _state.update { it.copy(error = UiText.Resource(Res.string.error_bad_code)) } }
    }

    fun confirm(companionUserId: String) = viewModelScope.launchReporting {
        coWatch.confirmCompanion(companionUserId)
    }

    fun rename(companionUserId: String, localName: String?) = viewModelScope.launchReporting {
        coWatch.setLocalName(companionUserId, localName)
    }

    fun unlink(companionUserId: String) = viewModelScope.launchReporting {
        coWatch.unlink(companionUserId)
    }

    fun setIncludeSeenByDefault(include: Boolean) = viewModelScope.launchReporting {
        coWatch.setIncludeSeenByDefault(include)
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
