package com.codingpit.muviss.feature.onboarding.domain

import com.codingpit.muviss.feature.onboarding.api.NotificationNudgeResult
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** The two per-device flags onboarding owns. Neither is synced. */
interface OnboardingRepository {
    fun observeCompleted(): Flow<Boolean>

    suspend fun markCompleted()

    fun observeNudgeDismissed(): Flow<Boolean>

    suspend fun dismissNudge()
}

/**
 * The Notification Nudge (EPIC 47, CONTEXT.md): when notifications are off and the user saves
 * their **first TV show**, Detail offers once, non-blockingly, to turn them on.
 *
 * Takes narrow inputs rather than the peer APIs so the rule is testable on its own. They are
 * [tvShows] (the library's TV titles, as ids), [notificationsEnabled], [canNotify] (false on web)
 * and [turnOn] (Settings' door, which asks the OS first).
 *
 * "At most once ever" is the dismissed flag, set by the ✕ and by "Turn on" alike, whatever the OS
 * answered: a user who refused once is not offered it again.
 */
class NotificationNudge(
    private val tvShows: Flow<List<MediaId>>,
    private val notificationsEnabled: Flow<Boolean>,
    private val repository: OnboardingRepository,
    private val canNotify: Boolean,
    private val turnOn: suspend () -> Boolean,
) {
    fun observe(mediaId: MediaId): Flow<Boolean> = combine(tvShows, notificationsEnabled, repository.observeNudgeDismissed()) { shows, enabled, dismissed ->
        canNotify && !enabled && !dismissed && shows.singleOrNull() == mediaId
    }

    suspend fun accept(): NotificationNudgeResult {
        // Spent even when asking throws or is cancelled: "at most once ever" holds for a
        // failed attempt too, and a cancelled coroutine's own I/O would stop before writing.
        val enabled = try {
            turnOn()
        } finally {
            withContext(NonCancellable) { repository.dismissNudge() }
        }
        return if (enabled) NotificationNudgeResult.ENABLED else NotificationNudgeResult.DENIED
    }

    suspend fun dismiss() = repository.dismissNudge()
}

class CompleteOnboardingUseCase(private val repository: OnboardingRepository) {
    suspend operator fun invoke() = repository.markCompleted()
}
