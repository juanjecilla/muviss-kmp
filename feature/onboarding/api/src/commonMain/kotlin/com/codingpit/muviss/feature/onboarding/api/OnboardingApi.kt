package com.codingpit.muviss.feature.onboarding.api

import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow

/**
 * What the rest of the app needs from onboarding (EPIC 47, ADR 0029): whether the
 * introduction has been seen, so the shell knows where to start, and the Notification
 * Nudge that Detail shows.
 */
interface OnboardingApi {
    /**
     * Whether this install has finished or skipped the introduction. An install that existed
     * before onboarding did is already "completed" (the migration says so); only a fresh one
     * starts at false.
     */
    fun observeCompleted(): Flow<Boolean>

    /** Ends the introduction, finished or skipped alike. Whatever its pages already wrote stays. */
    suspend fun markCompleted()

    /**
     * True while the Notification Nudge should be offered on [mediaId]'s Detail: notifications
     * are off, this is the user's first saved TV show, they have not dismissed the nudge before,
     * and this platform shows notifications at all.
     */
    fun observeNotificationNudge(mediaId: MediaId): Flow<Boolean>

    /** "Turn on": asks the OS and enables notifications. The nudge is spent either way. */
    suspend fun acceptNotificationNudge(): NotificationNudgeResult

    /** The ✕. Spent for good: the nudge is shown at most once, ever. */
    suspend fun dismissNotificationNudge()
}

enum class NotificationNudgeResult {
    ENABLED,

    /** The OS said no (on Android 13+ a refusal is sticky). The screen can point at system settings. */
    DENIED,
}
