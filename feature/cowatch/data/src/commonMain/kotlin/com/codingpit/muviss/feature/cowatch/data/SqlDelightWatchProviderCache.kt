package com.codingpit.muviss.feature.cowatch.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.cowatch.domain.CachedProviders
import com.codingpit.muviss.feature.cowatch.domain.WatchProviderCache
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.withContext

/**
 * Local storage for [WatchProviderCache], over `titleWatchProviders` (EPIC 41
 * follow-up, #122). Cache, not user data — see that table's KDoc — so unlike
 * every repository next to it in this package, there is no `isDirty`/`deleted`
 * to manage and nothing here is ever pushed.
 */
internal class SqlDelightWatchProviderCache(
    private val database: MuvissDatabase,
    private val clock: AppClock,
    private val dispatchers: AppDispatchers,
) : WatchProviderCache {

    private val queries = database.titleWatchProvidersQueries

    override suspend fun get(mediaIds: Set<MediaId>): Map<MediaId, CachedProviders> = withContext(dispatchers.io) {
        if (mediaIds.isEmpty()) return@withContext emptyMap()
        queries.selectByIds(mediaIds.map { it.toString() }).awaitAsList().associate { row ->
            MediaId.parse(row.mediaId) to CachedProviders(
                region = row.region,
                flatrateProviderIds = if (row.flatrateProviderIds.isBlank()) emptySet() else row.flatrateProviderIds.split(",").toSet(),
                fetchedAtEpochMs = row.fetchedAtEpochMs,
            )
        }
    }

    override suspend fun put(mediaId: MediaId, region: String, flatrateProviderIds: Set<String>) {
        withContext(dispatchers.io) {
            queries.upsert(
                mediaId = mediaId.toString(),
                region = region,
                flatrateProviderIds = flatrateProviderIds.joinToString(","),
                fetchedAtEpochMs = clock.nowEpochMs(),
            )
        }
    }
}
