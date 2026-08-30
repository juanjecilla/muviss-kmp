package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season

/**
 * [EpisodeCatalogStore] backed by a map, counting reads and writes so a test
 * can assert *which* source answered — the whole point of the read-through
 * being that the network is not it.
 */
class InMemoryEpisodeCatalogStore(
    initial: Map<MediaId, List<Season>> = emptyMap(),
) : EpisodeCatalogStore {

    val stored: MutableMap<MediaId, List<Season>> = initial.toMutableMap()
    var loadCount: Int = 0
        private set
    val savedIds: MutableList<MediaId> = mutableListOf()

    override suspend fun load(mediaIds: List<MediaId>): Map<MediaId, List<Season>> {
        loadCount++
        return mediaIds.mapNotNull { id -> stored[id]?.let { id to it } }.toMap()
    }

    override suspend fun save(mediaId: MediaId, seasons: List<Season>) {
        stored[mediaId] = seasons
        savedIds += mediaId
    }
}
