package com.codingpit.muviss.feature.cowatch.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType

/**
 * One title in a Watch Pool, in the shape both sides exchange (EPIC 41).
 *
 * This is the whole of what co-watch knows about a person's library, and the
 * shortness is the point (ADR 0022's third invariant). [started] and [seen] are
 * two bits: they say whether a title is a clean thing to begin together, and
 * nothing about how far, when, or how many times.
 *
 * [providerIds] (#122, EPIC 41 follow-up) is the other side of the same kind
 * of fact as [genres]: a denormalized, non-personal snapshot, sourced from
 * `WatchProviderCache` rather than `CollectionSummary`. A TMDB provider id is
 * the same entity in every region, so `ShortlistRanking` can intersect the
 * two sides' sets directly with no region ever needing to travel alongside
 * it. Defaults to empty for a device that has never cached the title's
 * providers — never a stale guess.
 */
data class PoolItem(
    val mediaId: MediaId,
    val mediaType: MediaType,
    val title: String,
    val posterUrl: String?,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val started: Boolean,
    val seen: Boolean,
    val pinned: Boolean,
    val providerIds: Set<String> = emptySet(),
)
