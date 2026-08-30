package com.codingpit.muviss.feature.progress.ui

import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogStore
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season

/**
 * Empty-by-default [EpisodeCatalogStore] for the ViewModel suites, which care
 * about screen state rather than about which source answered. The
 * read-through's own rules are asserted in `:domain`'s
 * `EpisodeCatalogCacheTest`, against a counting fake of the same name.
 */
class InMemoryEpisodeCatalogStore(
    initial: Map<MediaId, List<Season>> = emptyMap(),
) : EpisodeCatalogStore {

    private val stored = initial.toMutableMap()

    override suspend fun load(mediaIds: List<MediaId>): Map<MediaId, List<Season>> = mediaIds.mapNotNull { id -> stored[id]?.let { id to it } }.toMap()

    override suspend fun save(mediaId: MediaId, seasons: List<Season>) {
        stored[mediaId] = seasons
    }
}
