package com.codingpit.muviss.feature.cowatch.domain

import com.codingpit.muviss.feature.cowatch.api.ShortlistItem
import com.codingpit.muviss.feature.cowatch.api.ShortlistReason

/**
 * Turns two Watch Pools into the ranked Shortlist (EPIC 41, ADR 0022).
 *
 * A pure function of its inputs, deliberately. There is no weighted score here
 * and there should not be one: this app has no analytics and is not getting any
 * (#29 notes that adding them would itself reverse a stated product principle),
 * so a set of weights could never be tuned — only guessed at, then defended
 * forever. Explicit rules can be read, tested, and explained in one line of UI,
 * which is what makes a position something a person will believe.
 *
 * **Only the intersection appears.** A title one side has never heard of is not
 * a candidate for what to watch *together*, and surfacing it would turn the
 * Shortlist into a recommender fed by someone else's library — which is exactly
 * the sort of social feature ADR 0022 refuses.
 *
 * The order, highest first:
 *
 * 1. **Both pinned it.** Two deliberate acts, and the only signal in the model
 *    that someone actually asked for this title.
 * 2. **Neither has started it.** The cleanest thing to begin together; starting
 *    a series one person is four seasons into is not co-watching.
 * 3. **Everything else in both pools.**
 * 4. Ties break on the shorter runtime, then on title, so the order is stable
 *    rather than dependent on whichever pool happened to arrive first.
 */
object ShortlistRanking {

    fun rank(mine: List<PoolItem>, theirs: List<PoolItem>): List<ShortlistItem> {
        val theirsById = theirs.associateBy { it.mediaId }
        return mine.mapNotNull { ours ->
            val yours = theirsById[ours.mediaId] ?: return@mapNotNull null
            ours to yours
        }.map { (ours, yours) ->
            ShortlistItem(
                mediaId = ours.mediaId,
                mediaType = ours.mediaType,
                // Their copy is a snapshot of their library, ours of ours. Prefer
                // ours: it is the one this device can actually keep current.
                title = ours.title,
                posterUrl = ours.posterUrl ?: yours.posterUrl,
                runtimeMinutes = ours.runtimeMinutes ?: yours.runtimeMinutes,
                reasons = reasonsFor(ours, yours),
            )
        }.sortedWith(ORDER)
    }

    private fun reasonsFor(ours: PoolItem, yours: PoolItem): Set<ShortlistReason> = buildSet {
        if (ours.pinned && yours.pinned) add(ShortlistReason.BOTH_PINNED)
        if (!ours.started && !yours.started) add(ShortlistReason.NEITHER_STARTED)
        // A seen title only reaches a pool at all when its owner said they would
        // watch it again, or left it to the session default. Either way, saying
        // so is the honest explanation for why something already watched is here.
        if (ours.seen || yours.seen) add(ShortlistReason.REVISIT)
    }

    /**
     * Rank as a small integer rather than a float: there are three tiers, they
     * are named, and a number nobody can name is the thing this avoids.
     */
    private fun tier(item: ShortlistItem): Int = when {
        ShortlistReason.BOTH_PINNED in item.reasons -> 0
        ShortlistReason.NEITHER_STARTED in item.reasons -> 1
        else -> 2
    }

    private val ORDER = compareBy<ShortlistItem>(
        { tier(it) },
        // Unknown runtime sorts last rather than first: "we have an hour" is a
        // real question, and a title that cannot answer it should not win on it.
        { it.runtimeMinutes ?: Int.MAX_VALUE },
        { it.title },
    )
}
