package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.feature.settings.domain.ExternalIdResolver
import com.codingpit.muviss.feature.settings.domain.ExternalTitleRef
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.SourceId
import kotlinx.coroutines.withContext

/**
 * [ExternalIdResolver] over TMDB, the only registered [MetadataProviderRegistry]
 * source today (see EPIC 18 / `docs/IMPORT.md`'s TVmaze/Trakt groundwork
 * notes for future sources). A TMDB id maps directly — no network call,
 * [type] just picks the movie/tv suffix — while an IMDb id goes through
 * [com.codingpit.muviss.core.network.MetadataProvider.findByExternalId].
 * A [ref] with neither, or a TMDB id with no [type] to disambiguate the
 * movie/tv suffix, is unresolvable and returns null.
 */
class TmdbExternalIdResolver(
    private val registry: MetadataProviderRegistry,
    private val dispatchers: AppDispatchers,
) : ExternalIdResolver {

    override suspend fun resolve(ref: ExternalTitleRef, type: MediaType?): MediaId? = withContext(dispatchers.io) {
        ref.tmdbId?.let { tmdbId ->
            return@withContext when (type) {
                MediaType.MOVIE -> MediaId.tmdbMovie(tmdbId)
                MediaType.TV -> MediaId.tmdbTv(tmdbId)
                null -> null
            }
        }
        val imdbId = ref.imdbId ?: return@withContext null
        val provider = runCatching { registry.require(SourceId.TMDB) }.getOrNull() ?: return@withContext null
        runCatching { provider.findByExternalId(imdbId, type) }.getOrNull()?.id
    }
}
