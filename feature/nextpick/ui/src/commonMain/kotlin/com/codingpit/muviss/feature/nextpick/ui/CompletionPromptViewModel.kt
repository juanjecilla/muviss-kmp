package com.codingpit.muviss.feature.nextpick.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.feature.nextpick.domain.CompletionPromptPolicy
import com.codingpit.muviss.feature.nextpick.domain.CompletionPromptStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Owns the "What's next?" sheet's visibility (EPIC 51, #400). It is hosted at the app root, not on a
 * screen: `ProgressApi.completions` is hot with no replay, so a screen that is not composed at the moment
 * of a tick would miss it.
 *
 * A Completion opens the sheet only when [CompletionPromptPolicy] allows it: onboarding done, the Settings
 * switch on, and no earlier prompt today. The timestamp is written when the sheet appears, so "Not now" and
 * a swipe away both use up the day.
 */
class CompletionPromptViewModel(
    completions: Flow<com.codingpit.muviss.feature.progress.api.Completion>,
    onboardingCompleted: Flow<Boolean>,
    private val store: CompletionPromptStore,
    private val clock: AppClock,
) : ViewModel() {

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val onboardingDone = MutableStateFlow(false)

    init {
        viewModelScope.launchReporting { onboardingCompleted.collect { onboardingDone.value = it } }
        viewModelScope.launchReporting { completions.collect { onCompletion() } }
    }

    fun dismiss() {
        _visible.value = false
    }

    private suspend fun onCompletion() {
        val now = clock.nowEpochMs()
        val allowed = CompletionPromptPolicy.shouldShow(
            nowEpochMs = now,
            lastShownEpochMs = store.lastShownAtEpochMs(),
            enabled = store.enabled.first(),
            onboardingCompleted = onboardingDone.value,
        )
        if (!allowed) return
        store.markShown(now)
        _visible.value = true
    }
}
