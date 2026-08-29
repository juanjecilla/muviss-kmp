package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.first

/**
 * Re-fetches the episode catalog of every title currently being watched, and
 * stores it (ADR 0013).
 *
 * This is what keeps a home-screen widget honest between app launches: a
 * newly aired episode changes what "next unseen" means, and nothing else
 * would notice until someone opened the app. It runs on the background
 * refresh both platforms already schedule for new-episode notifications, so
 * it costs no new wake-ups.
 *
 * Deliberately *not* gated on the notification preference. Turning
 * notifications off is a statement about being interrupted, not about wanting
 * a stale widget.
 *
 * [collectionApi] is a provider for the same reason [WatchNextUseCase]'s is:
 * it would otherwise close a construction cycle through `ProgressApi`.
 */
class RefreshWatchNextCatalogsUseCase(
    private val collectionApi: () -> CollectionApi,
    private val catalogCache: EpisodeCatalogCache,
) {
    suspend operator fun invoke() {
        val watching = collectionApi().observeSummaries().first()
            .filter { it.status == WatchStatus.WATCHING }
            .map { it.mediaId }
        if (watching.isNotEmpty()) catalogCache.refresh(watching)
    }
}
