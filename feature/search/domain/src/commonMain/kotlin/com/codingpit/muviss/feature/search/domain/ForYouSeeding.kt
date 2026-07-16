package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary

/**
 * Picks the library titles that seed Discover's "For you" section (EPIC 16)
 * and merges the recommendations fetched for each seed back into one deduped
 * list. Pure and total — safe to unit-test without touching collection's
 * database or TMDB (see this module's `ForYouSeedingTest`) — `SearchViewModel`
 * is the only caller, reading the library through collection's `:api` per
 * ADR 0004.
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
     * deduplicated by [MediaId] (first occurrence wins), and with anything
     * already in [libraryIds] dropped — no point recommending what's already
     * saved.
     */
    fun mergeAndExclude(recommendationsBySeed: List<List<MediaSummary>>, libraryIds: Set<MediaId>): List<MediaSummary> = recommendationsBySeed
        .flatten()
        .distinctBy { it.id }
        .filterNot { it.id in libraryIds }
}
