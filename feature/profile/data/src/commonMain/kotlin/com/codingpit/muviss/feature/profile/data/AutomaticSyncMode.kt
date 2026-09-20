package com.codingpit.muviss.feature.profile.data

import com.codingpit.muviss.feature.profile.domain.AutomaticSyncMode

/**
 * How automatic sync actually runs on this target (ADR 0021), which is what
 * the switch's description has to be honest about: WorkManager on Android
 * runs with the app closed, iOS runs a system-scheduled refresh when it feels
 * like it, and desktop and web have only a timer that stops with the window.
 */
internal expect fun platformAutomaticSyncMode(): AutomaticSyncMode
