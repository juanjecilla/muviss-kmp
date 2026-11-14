package com.codingpit.muviss.feature.cowatch.data

import com.codingpit.muviss.core.common.crash.CrashReporter
import com.codingpit.muviss.core.network.NotFoundTitles
import com.codingpit.muviss.core.network.WatchProviderFill
import com.codingpit.muviss.feature.cowatch.domain.WatchProviderCache
import com.codingpit.muviss.feature.cowatch.domain.WatchProviderSource
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.CancellationException

/**
 * [WatchProviderFill] over the Shortlist's own TMDB source and cache (#762). One request per title, the same
 * request [TmdbWatchProviderSource] makes for the Shortlist, written through [WatchProviderCache.put] so the
 * two readers agree on what is cached. A failed write ends the batch like a failed fetch: no exception escapes
 * the fill, and cancellation is still rethrown.
 */
internal class TmdbWatchProviderFill(
    private val cache: WatchProviderCache,
    private val source: WatchProviderSource,
    private val notFound: NotFoundTitles,
) : WatchProviderFill {

    override suspend fun fill(mediaIds: List<MediaId>, region: String) {
        for (mediaId in mediaIds) {
            val result = source.fetchFlatrateIds(mediaId, region)
            val failure = result.exceptionOrNull()
            if (failure == null) {
                runCatching { cache.put(mediaId, region, result.getOrThrow()) }.onFailure { error ->
                    if (error is CancellationException) throw error
                    // A storage failure ends the batch like a failed fetch; the cache keeps what it already has.
                    CrashReporter.recordException(error)
                    return
                }
            } else if (failure is MetadataError.NotFound) {
                // Not an empty answer: the title is remembered as missing, so the next pick passes over it (#762).
                notFound.record(mediaId)
            } else {
                // Offline, rate-limited or unauthorised: the rest of the batch would fail the same way. Keep what is cached.
                return
            }
        }
    }
}
