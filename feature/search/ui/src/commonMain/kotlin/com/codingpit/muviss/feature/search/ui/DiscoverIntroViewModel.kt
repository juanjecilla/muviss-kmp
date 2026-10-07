package com.codingpit.muviss.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.feature.search.domain.SearchOnboarding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take

/**
 * The one-time Discover intro (EPIC 30, #73). Kept apart from
 * [SearchViewModel] because it is the screen's only piece of onboarding and
 * has nothing to do with searching.
 *
 * Only the first emission counts, as with triage's tutorial: dismissing must
 * not make the card reappear on the next database tick.
 */
class DiscoverIntroViewModel(private val onboarding: SearchOnboarding) : ViewModel() {

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    init {
        onboarding.observeIntroSeen()
            .take(1)
            .onEach { seen -> _visible.value = !seen }
            .launchInReporting(viewModelScope)
    }

    fun dismiss() {
        _visible.value = false
        viewModelScope.launchReporting { onboarding.setIntroSeen() }
    }
}
