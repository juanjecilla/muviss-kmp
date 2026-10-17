package com.codingpit.muviss.feature.settings.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The "show muted" chip's state (Muted Genres, ADR 0031). Held in memory in one Koin single so Triage and
 * Discover agree, and so it is gone with the process: a relaunch goes back to what Settings says.
 */
internal class MutedGenresSession {
    private val _showMuted = MutableStateFlow(false)
    val showMuted: StateFlow<Boolean> = _showMuted

    fun set(show: Boolean) {
        _showMuted.value = show
    }
}
