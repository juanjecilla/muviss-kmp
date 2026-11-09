package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.network.WatchProviderLookup
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.domain.PickCandidate
import com.codingpit.muviss.feature.nextpick.domain.PickCandidates
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.settings.api.TasteProfileApi

/**
 * "Only my services" (#663) for the candidates TMDB does not narrow itself: the backlog and the anchor's
 * recommendations (#740). `/discover` filters by provider on its side, so [DiscoverSource] is not wrapped.
 *
 * A candidate is kept only when its cached flatrate providers include one of the person's services. The
 * providers come from [WatchProviderLookup], which is cache hits only: no candidate is fetched to be checked,
 * so the request budget does not move. A title with no cached providers is EXCLUDED, whether it was never
 * fetched or was fetched for another region. The flag asks for titles known to be on one of the person's
 * services, and an unknown title is not one of those. A Taste Profile with no services narrows nothing,
 * which is what [DiscoverSource] already does with the same flag.
 *
 * The consequence is deliberate: a title only comes up under this flag once the Shortlist or a Co-watch
 * refresh has cached it. Whether the pick flow should fill that cache is a separate decision.
 */
internal class OwnServicesCandidates(
    private val inner: PickCandidates,
    private val taste: () -> TasteProfileApi,
    private val lookup: () -> WatchProviderLookup,
    private val region: () -> String,
) : PickCandidates {

    override suspend fun gather(criteria: PickCriteria, context: PickContext): List<PickCandidate> {
        val candidates = inner.gather(criteria, context)
        if (!criteria.onlyMyServices || candidates.isEmpty()) return candidates
        val services = serviceIds(taste()).mapTo(hashSetOf()) { it.toString() }
        if (services.isEmpty()) return candidates
        val cached = lookup().cachedFlatrateIds(candidates.mapTo(linkedSetOf()) { it.id }, region())
        return candidates.filter { candidate -> cached[candidate.id]?.any { it in services } == true }
    }
}
