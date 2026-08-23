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
 */
class TmdbDeckSource(
    private val registry: MetadataProviderRegistry,
    private val dispatchers: AppDispatchers,
) : DeckSource {
    override suspend fun page(type: MediaType, page: Int, genreId: String?): Result<PagedResult<MediaSummary>> = withContext(dispatchers.io) {
        runCatching { registry.require(SourceId.TMDB).discover(type, page, genreId) }
    }

    override suspend fun genres(type: MediaType): Result<List<Genre>> = withContext(dispatchers.io) {
        runCatching { registry.require(SourceId.TMDB).genres(type) }
    }
}
