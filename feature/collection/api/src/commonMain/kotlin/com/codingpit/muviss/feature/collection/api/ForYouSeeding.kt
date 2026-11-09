package com.codingpit.muviss.feature.collection.api

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary

/**
 * Picks the library titles that seed the personal suggestions (Discover's "For you",
 * EPIC 16, and a Pick Session that has no anchor, #661) and merges the recommendations
 * fetched for each seed back into one deduped list. Pure and total, so it is
 * unit-testable without collection's database or TMDB. It lives in collection's `:api`
 * because the library is what it reads, and both peers reach the library through there
 * (ADR 0004).
 */
object ForYouSeeding {
    /** A title counts as a "taste" signal once it's a favorite or rated at least this high (1-10 scale, EPIC 15). */
    const val TOP_RATED_THRESHOLD = 7

    /** Default cap on how many seeds get their own recommendations fetch — keeps Discover's load light. */
    const val DEFAULT_MAX_SEEDS = 3

    /**
     * Up to [maxSeeds] library titles to fetch recommendations for: favorites
     * and top-rated (>= [TOP_RATED_THRESHOLD]) titles, most recently added
     * first. Empty when the library carries no such signal yet — the caller
     * hides the "For you" section in that case rather than falling back to
     * something unrelated to the user's taste.
     */
    fun selectSeeds(library: List<CollectionSummary>, maxSeeds: Int = DEFAULT_MAX_SEEDS): List<MediaId> = library
        .asSequence()
        .filter { it.favorite || (it.rating ?: 0) >= TOP_RATED_THRESHOLD }
        .sortedByDescending { it.addedAtEpochMs }
        .map { it.mediaId }
        .distinct()
        .take(maxSeeds)
        .toList()

    /**
     * Merges the recommendation pages fetched for each seed into one list:
     * flattened in seed order (so the most recently-added seed's picks lead),
     * deduplicated by [MediaId] (first occurrence wins), and with anything in
     * [excludedIds] dropped.
     *
     * [excludedIds] is the library **plus** anything skipped during triage
     * (ADR 0010) **plus** anything snoozed (EPIC 42, ADR 0023): there is no
     * point recommending what is already saved, even less point suggesting
     * something the user explicitly rejected, and a title they have just asked
     * to be reminded about later should not be pushed at them now.
     * "For you" is the only discovery surface that filters any of these —
     * search results and the popular carousels stay whole, because those have
     * to return the catalogue, not a personalised view of it.
     */
    fun mergeAndExclude(recommendationsBySeed: List<List<MediaSummary>>, excludedIds: Set<MediaId>): List<MediaSummary> = recommendationsBySeed
        .flatten()
        .distinctBy { it.id }
        .filterNot { it.id in excludedIds }
}
