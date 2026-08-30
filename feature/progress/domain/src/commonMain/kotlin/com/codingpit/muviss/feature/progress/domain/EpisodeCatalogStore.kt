package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season

/**
 * Local storage for episode catalogs — the persisted half of
 * [EpisodeCatalogCache], implemented in `:data` over the `episode` table.
 *
 * Separate from [EpisodeCatalogSource] because the two answer different
 * questions: the source asks TMDB what a show contains, this remembers the
 * answer. Splitting them is what lets the cache read first and fetch only on
 * a miss, and it keeps this layer free of `:core:database` (ADR 0004 —
 * mirrors how [EpisodeCatalogSource] keeps `:core:network` out).
 *
 * A missing catalog is an empty list, never null: "this show has no stored
 * episodes" and "this show has no episodes" are the same thing to every
 * caller, and [EpisodeOrdering] already treats an empty season list as
 * "nothing to watch next".
 */
interface EpisodeCatalogStore {

    /** Stored catalogs for [mediaIds]; ids with nothing stored are absent from the result. */
    suspend fun load(mediaIds: List<MediaId>): Map<MediaId, List<Season>>

    /** Replaces [mediaId]'s stored catalog with [seasons], in one transaction. */
    suspend fun save(mediaId: MediaId, seasons: List<Season>)
}
