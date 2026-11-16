package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.network.NotFoundTitles
import com.codingpit.muviss.core.network.WatchProviderFill
import com.codingpit.muviss.core.network.WatchProviderLookup
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.domain.PickCandidate
import com.codingpit.muviss.feature.nextpick.domain.PickCandidates
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.settings.api.TasteProfileApi
import com.codingpit.muviss.models.MediaId

/**
 * The most titles one pick may ask TMDB about for "only my services" (#762). Each is one
 * `/watch/providers` request, and a pick already costs about six requests (genres, discover, recommendations,
 * similar). This keeps a pick near sixteen at worst, and it is per pick: the backlog, the anchor and the seed
 * titles share it (see [CombinedCandidates]), so a pick never costs three caps.
 *
 * Ten is a guess that trades coverage against the cost of a pick. At ten titles per pick, a backlog of a
 * few hundred is covered after a few dozen picks, and every title covered stays covered until its cache
 * entry is refreshed. Pinned in `NextPickRequestBudgetTest`.
 */
internal const val MAX_PROVIDER_FILLS_PER_PICK = 10

/**
 * "Only my services" (#663) for the candidates TMDB does not narrow itself: the backlog and the anchor's
 * recommendations (#740). `/discover` filters by provider on its side, so [DiscoverSource] is not wrapped.
 *
 * A candidate is kept only when its cached flatrate providers include one of the person's services. Each
 * candidate is one of three outcomes:
 *
 * - **Cached for this region**: judged on what is cached. Stale entries count as cached; the Shortlist's
 *   refresher is what refreshes them.
 * - **Uncached, and inside the budget**: fetched through [WatchProviderFill] before the filter runs, and then
 *   judged on the fetched answer. Fetching is done in candidate order, so successive picks reach further down
 *   the library. A title already cached is never fetched again by this path.
 * - **Unknown after the budget, or because the fetch failed**: EXCLUDED from this pick. It is not claimed as
 *   available (the flag asks for titles known to be on a service) and it is not silently treated as on none:
 *   it stays uncached, so a later pick fetches it when the budget reaches it.
 *
 * Offline, nothing is fetched and the filter is cache-only, so the pick works from what the device already
 * knows. A TMDB failure is never read as an empty answer, so a failed fill cannot record a title as on no
 * service.
 *
 * A Taste Profile with no services narrows nothing, which is what [DiscoverSource] already does with the same
 * flag.
 */
internal class OwnServicesCandidates(
    private val inner: PickCandidates,
    private val taste: () -> TasteProfileApi,
    private val lookup: () -> WatchProviderLookup,
    private val fill: () -> WatchProviderFill,
    private val region: () -> String,
    private val missing: () -> NotFoundTitles,
) : PickCandidates {

    override suspend fun gather(criteria: PickCriteria, context: PickContext): List<PickCandidate> {
        val candidates = inner.gather(criteria, context)
        if (!criteria.onlyMyServices || candidates.isEmpty()) return candidates
        val services = serviceIds(taste()).mapTo(hashSetOf()) { it.toString() }
        if (services.isEmpty()) return candidates
        val regionCode = region()
        val ids = candidates.mapTo(linkedSetOf()) { it.id }
        val known = if (context.online) fillUnknown(ids, regionCode) else cachedFor(ids, regionCode)
        return candidates.filter { candidate -> known[candidate.id]?.any { it in services } == true }
    }

    /**
     * Fetches the first [MAX_PROVIDER_FILLS_PER_PICK] titles the cache has no answer for in [region], then re-reads.
     * A title TMDB recently said does not exist is passed over, so the budget reaches the titles behind it (#762).
     */
    private suspend fun fillUnknown(ids: Set<MediaId>, regionCode: String): Map<MediaId, Set<String>> {
        val cached = cachedFor(ids, regionCode)
        val uncached = ids.filterNot { it in cached }
        val recentlyMissing = missing().known(uncached.toSet())
        val unknown = uncached.filterNot { it in recentlyMissing }.take(MAX_PROVIDER_FILLS_PER_PICK)
        if (unknown.isEmpty()) return cached
        fill().fill(unknown, regionCode)
        return cachedFor(ids, regionCode)
    }

    /** Cache hits only, in batches: one `IN (...)` binds one parameter per id, and a library can be far larger than SQLite's old limit of 999 (CodeRabbit, #764). */
    private suspend fun cachedFor(ids: Set<MediaId>, regionCode: String): Map<MediaId, Set<String>> = buildMap {
        ids.chunked(LOOKUP_BATCH).forEach { batch -> putAll(lookup().cachedFlatrateIds(batch.toSet(), regionCode)) }
    }

    private companion object {
        const val LOOKUP_BATCH = 500
    }
}

/**
 * The backlog, the anchor's recommendations and the seed titles gathered as one pool, so that
 * [OwnServicesCandidates] spends one budget on all of them (#762). The engine merges by title and source
 * regardless of how sources are grouped, so grouping them changes nothing it decides. Gathered in order, one
 * after another, so nothing here is shared mutable state.
 */
internal class CombinedCandidates(private val sources: List<PickCandidates>) : PickCandidates {
    override suspend fun gather(criteria: PickCriteria, context: PickContext): List<PickCandidate> = sources.flatMap { it.gather(criteria, context) }
}
