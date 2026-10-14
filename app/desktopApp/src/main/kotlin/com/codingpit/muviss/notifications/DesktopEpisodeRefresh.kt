package com.codingpit.muviss.notifications

import com.codingpit.muviss.NotificationText
import com.codingpit.muviss.ResourceNotificationText
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.settings.api.SettingsApi
import kotlinx.coroutines.flow.first
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Desktop's counterpart to `:app:androidApp`'s `NewEpisodesWorker` and iOS's
 * `IosBackgroundRefresh` (EPIC 5).
 *
 * It mirrors that worker's `doWork()` step for step on purpose. The worker is
 * deliberately thin because every decision worth unit-testing already lives in
 * `feature/collection/domain`'s `NewEpisodesCalculator` /
 * `RefreshAndFindNewEpisodesUseCase`, reached only through
 * [CollectionApi.refreshAndFindNewEpisodes]; the same is true here, which is
 * why this class holds no logic beyond the ordering and the gate.
 *
 * **What it cannot do:** Android has WorkManager and iOS has `BGTaskScheduler`,
 * so both notify with the app shut. A desktop app that is not running cannot,
 * and nothing here changes that — this ticks only while a window is open. The
 * Settings toggle's description says so; a login item / launchd / Task Scheduler
 * entry is the real fix and is filed separately.
 */
class DesktopEpisodeRefresh(
    private val collectionApi: CollectionApi,
    private val settingsApi: SettingsApi,
    private val progressApi: ProgressApi,
) {

    /**
     * One pass. Returns the shows worth telling the user about, empty when
     * there is nothing new or notifications are off.
     *
     * Never throws: a failed refresh is a quiet no-result, exactly as the
     * worker turns it into `Result.retry()`. This runs on a timer nobody asked
     * for, and it may not take the window down with it.
     */
    suspend fun runOnce(): List<NewEpisodesResult> {
        // Ahead of the notification gate on purpose, matching the worker: the
        // catalog feeds the watch-next surface, and turning notifications off
        // is a statement about being interrupted, not about wanting stale data.
        runCatching { progressApi.refreshWatchNextCatalogs() }

        val notificationsEnabled = runCatching { settingsApi.observeNotificationsEnabled().first() }.getOrDefault(false)
        if (!notificationsEnabled) return emptyList()

        return runCatching { collectionApi.refreshAndFindNewEpisodes() }.getOrDefault(emptyList())
    }

    companion object {
        /** Matches the Android worker's period, so the two platforms behave alike while both are awake. */
        val INTERVAL: Duration = 12.hours
    }
}

/** Title and body for one tray notification. Separated from the sending so it can be tested without a display. */
data class EpisodeNotification(val title: String, val message: String)

/**
 * Collapses a refresh's results into the single notification desktop gets.
 *
 * Android posts one notification per show plus a group summary, because its
 * notification shade groups and de-duplicates them. A tray balloon has no
 * grouping and no shade — a user with a big library returning after a week
 * would get a burst of popups that overwrite each other — so desktop always
 * sends exactly one, and lets the app itself carry the detail.
 *
 * Returns null when there is nothing to say, so the caller has no branch of
 * its own to get wrong.
 */
suspend fun newEpisodesNotification(results: List<NewEpisodesResult>, text: NotificationText = ResourceNotificationText): EpisodeNotification? = when {
    results.isEmpty() -> null

    results.size == 1 -> {
        val only = results.single()
        EpisodeNotification(
            title = only.title,
            // Same wording as the Android notifier's contentText(), whose title
            // likewise already carries the show's name.
            message = only.latestEpisodeLabel?.let { text.episodeOut(it) } ?: text.nowAvailable(),
        )
    }

    else -> EpisodeNotification(
        title = text.showsWithNewEpisodes(results.size),
        message = results.joinToString(", ") { it.title },
    )
}
