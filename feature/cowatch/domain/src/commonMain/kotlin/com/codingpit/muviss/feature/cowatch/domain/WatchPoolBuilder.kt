package com.codingpit.muviss.feature.cowatch.domain

import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.cowatch.api.PoolSettings
import com.codingpit.muviss.feature.cowatch.api.PoolSource
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus

/**
 * Decides what this account publishes to a Companion (EPIC 41, ADR 0022).
 *
 * Pure, and the most privacy-sensitive function in the feature: whatever comes
 * out of here leaves the device. Everything the library knows and this does not
 * return — ticks, plays, ratings, notes, dates, how far into a season someone
 * is — stays put, which is the whole of ADR 0022's third invariant.
 *
 * Three rules decide membership:
 *
 * 1. **The source.** By default, entries the user has not started: "I mean to
 *    watch this" is already what NotStarted means here (CONTEXT.md), so the
 *    feature works before anyone curates anything. A named list replaces that
 *    for someone who does not want their whole watchlist in play.
 * 2. **Already-seen titles join only by willingness.** An explicit Revisit
 *    Willingness answer decides; an unanswered title falls back to the
 *    per-device default. Without this rule everything either person has
 *    finished would be invisible, and that is most of what two people actually
 *    rewatch together.
 * 3. **An explicit "no" always wins.** A `false` answer keeps a title out
 *    whatever the default says. A default is a convenience; an answer is an
 *    instruction.
 *
 * Titles in progress are deliberately absent. Someone four seasons into a show
 * is not a candidate to *start* it with you, and publishing it would leak how
 * far along they are for no gain.
 */
object WatchPoolBuilder {

    fun build(
        summaries: List<CollectionSummary>,
        settings: PoolSettings,
        namedListContents: Set<MediaId> = emptySet(),
        /** Sourced from `WatchProviderCache`/`WatchProviderRefresher` (#122). A title absent from this map publishes an empty provider set rather than blocking on a fetch. */
        providerIdsByMediaId: Map<MediaId, Set<String>> = emptyMap(),
    ): List<PoolItem> = summaries.filter { include(it, settings, namedListContents) }
        .map { it.toPoolItem(providerIdsByMediaId[it.mediaId].orEmpty()) }

    private fun include(
        summary: CollectionSummary,
        settings: PoolSettings,
        namedListContents: Set<MediaId>,
    ): Boolean {
        if (summary.isSeen) return willingToRevisit(summary, settings)
        // An explicit "no" is about revisiting, so it has nothing to say about a
        // title nobody has watched yet.
        return when (val source = settings.source) {
            is PoolSource.NotStarted -> summary.status == WatchStatus.NOT_STARTED
            is PoolSource.Named -> summary.mediaId in namedListContents && summary.status != WatchStatus.WATCHING
        }
    }

    private fun willingToRevisit(summary: CollectionSummary, settings: PoolSettings): Boolean = summary.revisitWillingness ?: settings.includeSeenByDefault

    private val CollectionSummary.isSeen: Boolean
        get() = status == WatchStatus.WATCHED || status == WatchStatus.FINISHED

    private fun CollectionSummary.toPoolItem(providerIds: Set<String>) = PoolItem(
        mediaId = mediaId,
        mediaType = mediaId.type,
        title = title,
        posterUrl = posterUrl,
        genres = genres,
        runtimeMinutes = runtimeMinutes,
        started = status != WatchStatus.NOT_STARTED,
        seen = isSeen,
        pinned = coWatchPinned,
        providerIds = providerIds,
    )
}
