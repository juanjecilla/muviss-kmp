package com.codingpit.muviss.feature.triage.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.feature.triage.domain.DeckSource
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.SourceId
import kotlinx.coroutines.withContext

/**
 * Draws deck candidates from TMDB's `/discover`, which is ordered by
 * popularity descending. That ordering is the whole reason the backfill phase
 * works: the first cards a new user sees are titles they almost certainly have
 * an opinion about, and the feed degrades into a long tail rather than ending.
 * It also keeps working for the trickle phase, because popularity churns as
 * new titles release.
 *
 * `/discover` reports a `total_pages` in the thousands but refuses any page
 * past [MAX_DISCOVER_PAGE] (HTTP 422), so the reported count is clamped here:
 * the deck's cursor then reads the last servable page as the end of the
 * catalogue and the deck says "caught up", where it would otherwise ask for
 * page 501 and surface a load error.
 */
class TmdbDeckSource(
    private val registry: MetadataProviderRegistry,
    private val dispatchers: AppDispatchers,
) : DeckSource {
    override suspend fun page(type: MediaType, page: Int, genreId: String?): Result<PagedResult<MediaSummary>> = withContext(dispatchers.io) {
        runCatching {
            val result = registry.require(SourceId.TMDB).discover(type, page, genreId)
            result.copy(totalPages = minOf(result.totalPages, MAX_DISCOVER_PAGE))
        }
    }

    override suspend fun genres(type: MediaType): Result<List<Genre>> = withContext(dispatchers.io) {
        runCatching { registry.require(SourceId.TMDB).genres(type) }
    }

    companion object {
        /** TMDB's hard ceiling on `page` for `/discover`. */
        const val MAX_DISCOVER_PAGE = 500
    }
}
