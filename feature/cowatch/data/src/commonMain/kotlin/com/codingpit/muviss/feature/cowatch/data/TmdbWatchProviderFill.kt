package com.codingpit.muviss.feature.cowatch.data

import com.codingpit.muviss.core.network.WatchProviderFill
import com.codingpit.muviss.feature.cowatch.domain.WatchProviderCache
import com.codingpit.muviss.feature.cowatch.domain.WatchProviderSource
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MetadataError

/**
 * [WatchProviderFill] over the Shortlist's own TMDB source and cache (#762). One request per title, the same
 * request [TmdbWatchProviderSource] makes for the Shortlist, written through [WatchProviderCache.put] so the
 * two readers agree on what is cached.
 */
internal class TmdbWatchProviderFill(
    private val cache: WatchProviderCache,
    private val source: WatchProviderSource,
) : WatchProviderFill {

    override suspend fun fill(mediaIds: List<MediaId>, region: String) {
        for (mediaId in mediaIds) {
            val result = source.fetchFlatrateIds(mediaId)
            val failure = result.exceptionOrNull()
            if (failure == null) {
                cache.put(mediaId, region, result.getOrThrow())
            } else if (failure !is MetadataError.NotFound) {
                // Offline, rate-limited or unauthorised: the rest of the batch would fail the same way. Keep what is cached.
                return
            }
        }
    }
}
