package com.codingpit.muviss.core.network

import com.codingpit.muviss.models.MediaId

/**
 * The request-making counterpart of [WatchProviderLookup] (#762): fetches where titles stream and writes
 * the answer into the same title-level cache, so a pick can ask about titles the cache has never seen.
 *
 * It is a separate interface on purpose. [WatchProviderLookup] promises that nothing behind it makes a
 * request, and the "only my services" filter relies on that promise everywhere except the one caller that
 * has a request budget. Only that caller holds this interface.
 *
 * Implemented in `:feature:cowatch:data` over the same TMDB source the Shortlist uses, so there is no second
 * HTTP path, and [TmdbProvider][com.codingpit.muviss.core.network.tmdb.TmdbProvider]'s in-flight cap applies.
 */
interface WatchProviderFill {
    /**
     * Fetches and caches the flatrate provider ids of [mediaIds] for [region], one request per title, in the
     * order given. Titles that are already cached are the caller's business: this writes whatever it is given.
     *
     * A failed fetch writes nothing for that title, so it stays unknown rather than being recorded as "on no
     * service". The first failure other than "no such title" ends the call: a connection that dropped or a
     * rate limit will fail the rest too, and the cache keeps what it already had. No exception escapes, and
     * the failure's text is never read.
     */
    suspend fun fill(mediaIds: List<MediaId>, region: String)
}
