package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.feature.settings.domain.GenreCatalog
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.SourceId
import kotlinx.coroutines.withContext

internal class TmdbGenreCatalog(
    private val registry: MetadataProviderRegistry,
    private val dispatchers: AppDispatchers,
) : GenreCatalog {
    override suspend fun genres(type: MediaType): Result<List<Genre>> = withContext(dispatchers.io) {
        runCatching { registry.require(SourceId.TMDB).genres(type) }
    }

    override suspend fun examples(type: MediaType, genreId: String, limit: Int): Result<List<MediaSummary>> = withContext(dispatchers.io) {
        runCatching { registry.require(SourceId.TMDB).discover(type, page = 1, genreId = genreId).items.take(limit) }
    }
}
