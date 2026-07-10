package com.codingpit.muviss.feature.search.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.SourceId
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.withContext

/**
 * Routes discovery calls to the correct [MetadataProvider] via the registry.
 * Search/trending/discover/genres default to TMDB; detail and watch-provider
 * lookups route by the id's own source, so a future provider works with no
 * change here. [locale] supplies the region used for watch-provider lookups
 * (see [MetadataLocale]).
 */
class TmdbSearchRepository(
    private val registry: MetadataProviderRegistry,
    private val dispatchers: AppDispatchers,
    private val locale: MetadataLocale,
) : SearchRepository {

    override suspend fun search(query: String, page: Int): Result<PagedResult<MediaSummary>> = runOnIo { registry.require(SourceId.TMDB).search(query, page) }

    override suspend fun trending(): Result<List<MediaSummary>> = runOnIo { registry.require(SourceId.TMDB).trending() }

    override suspend fun details(id: MediaId): Result<MediaDetails> = runOnIo { registry.forId(id).details(id) }

    override suspend fun discover(type: MediaType, page: Int, genreId: String?): Result<PagedResult<MediaSummary>> = runOnIo { registry.require(SourceId.TMDB).discover(type, page, genreId) }

    override suspend fun genres(type: MediaType): Result<List<Genre>> = runOnIo { registry.require(SourceId.TMDB).genres(type) }

    override suspend fun watchProviders(id: MediaId): Result<WatchProviders> = runOnIo { registry.forId(id).watchProviders(id, locale.region) }

    private suspend fun <T> runOnIo(block: suspend () -> T): Result<T> = withContext(dispatchers.io) { runCatching { block() } }
}
