package com.codingpit.muviss.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.settings.api.SettingsApi
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Periodic background refresh for EPIC 5 ("new-episode notifications").
 * Scheduled by [NewEpisodesScheduler] roughly every 12h with network +
 * battery-not-low constraints (Doze-friendly).
 *
 * Resolves its dependencies via [KoinComponent] rather than a constructor:
 * WorkManager's default `WorkerFactory` instantiates workers by reflection
 * on their `(Context, WorkerParameters)` constructor, so Koin can't be
 * injected the way a `koinViewModel<T>()` is. This requires Koin to already
 * be running globally, which `MuvissApplication.onCreate()` guarantees even
 * on a cold start with no Activity/Compose tree yet (e.g. a periodic run
 * while the app was never opened since boot).
 *
 * Kept intentionally thin so no Robolectric/instrumentation test is needed:
 * every decision worth unit-testing — the refresh, per-show mute, and
 * before/after diff — lives in `feature/collection/domain`'s
 * `NewEpisodesCalculator`/`RefreshAndFindNewEpisodesUseCase`, reached here
 * only through [CollectionApi.refreshAndFindNewEpisodes]. This class' own
 * job is just: check the global toggle, call that, and hand the result to
 * [NewEpisodesNotifier].
 */
class NewEpisodesWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {

    private val collectionApi: CollectionApi by inject()
    private val settingsApi: SettingsApi by inject()

    override suspend fun doWork(): Result {
        val notificationsEnabled = settingsApi.observeNotificationsEnabled().first()
        if (!notificationsEnabled) return Result.success()

        val newEpisodes = runCatching { collectionApi.refreshAndFindNewEpisodes() }
            .getOrElse { return Result.retry() }

        if (newEpisodes.isNotEmpty()) {
            NewEpisodesNotifier(applicationContext).notify(newEpisodes)
        }
        return Result.success()
    }
}
