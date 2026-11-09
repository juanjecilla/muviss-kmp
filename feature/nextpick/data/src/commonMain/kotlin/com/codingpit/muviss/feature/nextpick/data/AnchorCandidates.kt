package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.domain.CandidateSource
import com.codingpit.muviss.feature.nextpick.domain.PickCandidate
import com.codingpit.muviss.feature.nextpick.domain.PickCandidates
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.first

/**
 * TMDB's recommendations and similar titles for the title just finished (EPIC 51, #548, ADR 0032): "more
 * like it". Asked only when the person chose the anchor's "more like it" answer, online, and not for "my list".
 *
 * Budget: at most two requests a pick, one `/recommendations` and one `/similar`, both page 1. Nothing is
 * fetched for "something different" (the engine would only penalise it), and nothing when the requested
 * format is not the anchor's own: TMDB answers a show's recommendations with shows only.
 *
 * Each candidate carries its library status, as [DiscoverSource] does, so a title the library already knows
 * is never mistaken for something new. A failed call is no candidates from that one source, not an error for
 * the session: the backlog and `/discover` still answer.
 */
internal class AnchorCandidates(
    private val metadata: MetadataProvider,
    private val collection: () -> CollectionApi,
) : PickCandidates {

    override suspend fun gather(criteria: PickCriteria, context: PickContext): List<PickCandidate> {
        val anchor = criteria.anchor?.takeIf { it.like } ?: return emptyList()
        if (!context.online || criteria.source == PickSource.MY_LIST) return emptyList()
        if (criteria.type != null && criteria.type != anchor.mediaId.type) return emptyList()

        val statuses = collection().observeSummaries().first().associate { it.mediaId to it.status }
        val recommended = orEmptyOnFailure { metadata.recommendations(anchor.mediaId).items }
        val similar = orEmptyOnFailure { metadata.similar(anchor.mediaId).items }
        return (recommended.toCandidates(CandidateSource.RECOMMENDED, statuses) + similar.toCandidates(CandidateSource.SIMILAR, statuses))
            .filter { it.id != anchor.mediaId }
    }

    private fun List<MediaSummary>.toCandidates(source: CandidateSource, statuses: Map<MediaId, WatchStatus>): List<PickCandidate> = map { summary -> PickCandidate(summary = summary, source = source, libraryStatus = statuses[summary.id]) }

    /** Any [MetadataError] is no candidates from this source. Anything else is a bug, and surfaces. */
    @Suppress("SwallowedException") // The failure is the offline or rate-limited answer; the session falls back to the other sources.
    private suspend fun orEmptyOnFailure(fetch: suspend () -> List<MediaSummary>): List<MediaSummary> = try {
        fetch()
    } catch (failure: MetadataError) {
        emptyList()
    }
}
