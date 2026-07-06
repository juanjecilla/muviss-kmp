package com.codingpit.muviss.feature.search.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.SourceId
import kotlinx.coroutines.withContext

/**
 * Routes discovery calls to the correct [MetadataProvider] via the registry.
 * Search/trending default to TMDB; detail lookups route by the id's own source,
 * so a future provider works with no change here.
 */
class TmdbSearchRepository(
    private val registry: MetadataProviderRegistry,
    private val dispatchers: AppDispatchers,
) : SearchRepository {

    override suspend fun search(query: String): Result<List<MediaSummary>> = runOnIo { registry.require(SourceId.TMDB).search(query) }

    override suspend fun trending(): Result<List<MediaSummary>> = runOnIo { registry.require(SourceId.TMDB).trending() }

    override suspend fun details(id: MediaId): Result<MediaDetails> = runOnIo { registry.forId(id).details(id) }

    private suspend fun <T> runOnIo(block: suspend () -> T): Result<T> = withContext(dispatchers.io) { runCatching { block() } }
}
