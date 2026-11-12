package com.codingpit.muviss.core.network

import com.codingpit.muviss.models.MediaId

/**
 * Read-only access to the title-level cache of where a title streams (#740, EPIC 41 follow-up #122).
 *
 * It lives here, not in a feature's `:api`, because the cache is about titles, not about any one feature:
 * Co-watch fills it for the Shortlist, Next Pick reads it for "only my services", and neither owns it. The
 * implementation is over `titleWatchProviders` in `:feature:cowatch:data`.
 *
 * Cache hits only. Nothing behind this interface makes a request, so a caller that asks about 200 titles
 * costs no TMDB calls: a title never fetched is simply absent from the answer.
 */
interface WatchProviderLookup {
    /**
     * The cached flatrate provider ids for [mediaIds], for [region] only. A title that was never cached,
     * or whose cached entry is for another region, is absent from the map, so a caller decides what
     * "unknown" means for it.
     */
    suspend fun cachedFlatrateIds(mediaIds: Set<MediaId>, region: String): Map<MediaId, Set<String>>
}
