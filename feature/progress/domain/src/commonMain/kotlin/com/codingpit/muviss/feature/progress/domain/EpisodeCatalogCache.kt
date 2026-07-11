package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory, process-lifetime cache of each show's season/episode catalog,
 * shared by every screen in the progress feature that needs one (watch-next
 * and the Upcoming agenda, EPIC 14) so a show fetched for one screen doesn't
 * get fetched again for the other — the two are simply different views over
 * the same "what episodes does this show have" data. Not persisted; cold
 * each app launch, the same trade-off watch-next already made on its own
 * before this cache existed.
 */
class EpisodeCatalogCache(private val fetchEpisodeCatalog: FetchEpisodeCatalogUseCase) {

    private val _catalogs = MutableStateFlow<Map<MediaId, List<Season>>>(emptyMap())
    val catalogs: StateFlow<Map<MediaId, List<Season>>> = _catalogs

    /** Fetches every id in [mediaIds] not already cached; leaves the rest untouched. */
    suspend fun loadMissing(mediaIds: List<MediaId>) {
        val missing = mediaIds.filterNot { _catalogs.value.containsKey(it) }
        fetchAndStore(missing)
    }

    /** Re-fetches [mediaIds] (every currently cached id by default) — the pull-to-refresh action. */
    suspend fun refresh(mediaIds: List<MediaId> = _catalogs.value.keys.toList()) {
        fetchAndStore(mediaIds)
    }

    private suspend fun fetchAndStore(mediaIds: List<MediaId>) {
        mediaIds.forEach { id ->
            fetchEpisodeCatalog(id).onSuccess { seasons -> _catalogs.update { it + (id to seasons) } }
        }
    }
}
