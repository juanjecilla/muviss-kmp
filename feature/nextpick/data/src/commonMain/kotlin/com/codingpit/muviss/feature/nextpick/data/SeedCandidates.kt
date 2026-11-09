package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.ForYouSeeding
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
 * "More like what I already love" for a Pick Session with no anchor (#661, ADR 0032): TMDB's recommendations
 * and similar titles for the library's taste seeds, the same seeds Discover's "For you" uses
 * ([ForYouSeeding.selectSeeds]). Without this, a "something new" session never drew on what the person likes.
 *
 * Budget: at most [MAX_SEEDS] seeds, and two requests a seed (`/recommendations` and `/similar`, both page 1),
 * so six at most a pick. Offline and "my list" make no request, and nothing is fetched when an anchor is set:
 * the anchored source already covers that case. A seed whose format the person did not ask for is skipped, as
 * TMDB answers a show's recommendations with shows only.
 *
 * Each candidate carries its library status, so a title the library already knows is never mistaken for new.
 * A failed call is no candidates from that one request, not an error for the session.
 */
internal class SeedCandidates(
    private val metadata: MetadataProvider,
    private val collection: () -> CollectionApi,
) : PickCandidates {

    override suspend fun gather(criteria: PickCriteria, context: PickContext): List<PickCandidate> {
        if (criteria.anchor != null || !context.online || criteria.source == PickSource.MY_LIST) return emptyList()

        val library = collection().observeSummaries().first()
        val statuses = library.associate { it.mediaId to it.status }
        val seeds = ForYouSeeding.selectSeeds(library, maxSeeds = MAX_SEEDS)
            .filter { criteria.type == null || criteria.type == it.type }
        return seeds.flatMap { seed ->
            val recommended = orEmptyOnFailure { metadata.recommendations(seed).items }
            val similar = orEmptyOnFailure { metadata.similar(seed).items }
            (recommended.toCandidates(CandidateSource.RECOMMENDED, statuses) + similar.toCandidates(CandidateSource.SIMILAR, statuses))
                .filter { it.id != seed }
        }
    }

    private fun List<MediaSummary>.toCandidates(source: CandidateSource, statuses: Map<MediaId, WatchStatus>): List<PickCandidate> = map { summary ->
        PickCandidate(summary = summary, source = source, libraryStatus = statuses[summary.id])
    }

    /** Any [MetadataError] is no candidates from this request. Anything else is a bug, and surfaces. */
    @Suppress("SwallowedException") // The failure is the offline or rate-limited answer; the session falls back to the other sources.
    private suspend fun orEmptyOnFailure(fetch: suspend () -> List<MediaSummary>): List<MediaSummary> = try {
        fetch()
    } catch (failure: MetadataError) {
        emptyList()
    }

    companion object {
        /** The seeds a no-anchor session asks TMDB about: the same cap "For you" uses, so the two cost the same. */
        const val MAX_SEEDS = ForYouSeeding.DEFAULT_MAX_SEEDS
    }
}
