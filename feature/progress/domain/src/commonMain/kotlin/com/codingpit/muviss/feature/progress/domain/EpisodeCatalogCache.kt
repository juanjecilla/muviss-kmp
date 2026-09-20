package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.core.common.concurrency.mapBounded
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Each show's season/episode catalog, shared by every caller that needs one —
 * watch-next, the Upcoming agenda (EPIC 14) and the home-screen widgets
 * (EPIC 22) are simply different views over the same "what episodes does this
 * show have" data, so a show fetched for one is not fetched again for another.
 *
 * Read-through as of EPIC 22: [loadMissing] consults [store] before it
 * consults the network, and anything fetched is written back. Until then this
 * was memory-only and cold on every launch, which meant "next unseen episode"
 * could not be computed without a network round trip — fine for a screen the
 * user just opened, impossible for a widget refreshing with no app running.
 * See ADR 0015.
 *
 * The in-memory [catalogs] flow stays in front of the store rather than being
 * replaced by one: it is what makes a catalog arriving mid-session push
 * itself into an already-composed screen, and it keeps repeat reads off the
 * database entirely.
 */
class EpisodeCatalogCache(
    private val fetchEpisodeCatalog: FetchEpisodeCatalogUseCase,
    private val store: EpisodeCatalogStore,
) {

    private val _catalogs = MutableStateFlow<Map<MediaId, List<Season>>>(emptyMap())
    val catalogs: StateFlow<Map<MediaId, List<Season>>> = _catalogs

    /**
     * Makes every id in [mediaIds] available in [catalogs], cheapest source
     * first: already in memory, then stored locally, then fetched. Ids
     * already cached are left untouched.
     */
    suspend fun loadMissing(mediaIds: List<MediaId>) {
        val absent = mediaIds.filterNot { _catalogs.value.containsKey(it) }
        if (absent.isEmpty()) return

        val stored = store.load(absent)
        if (stored.isNotEmpty()) _catalogs.update { it + stored }

        fetchAndStore(absent.filterNot { stored.containsKey(it) })
    }

    /**
     * Re-fetches [mediaIds] (every currently cached id by default) and writes
     * the result back — the pull-to-refresh action, and what the background
     * refresh calls so a widget keeps naming the right episode after new ones
     * air.
     */
    suspend fun refresh(mediaIds: List<MediaId> = _catalogs.value.keys.toList()) {
        fetchAndStore(mediaIds)
    }

    /**
     * Titles are fetched a few at a time (`REFRESH_CONCURRENCY`), as the library
     * refresh does, rather than one after another.
     *
     * A failed fetch leaves both the store and [catalogs] as they were: a
     * stale catalog names a real episode, an absent one names nothing, so
     * losing what is stored because the network was unavailable is strictly
     * worse than keeping it.
     */
    private suspend fun fetchAndStore(mediaIds: List<MediaId>) {
        mediaIds.mapBounded { id ->
            fetchEpisodeCatalog(id).onSuccess { seasons ->
                store.save(id, seasons)
                _catalogs.update { it + (id to seasons) }
            }
        }
    }
}
