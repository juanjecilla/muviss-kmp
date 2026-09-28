package com.codingpit.muviss.feature.cowatch.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.feature.cowatch.domain.WatchProviderSource
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.withContext

/**
 * Routes watch-provider lookups to the right
 * [MetadataProvider][com.codingpit.muviss.core.network.MetadataProvider] via
 * the registry, mirroring `TmdbSearchRepository.watchProviders` (EPIC 41
 * follow-up, #122) — only `flatrate` is kept, per `TitleWatchProviders.sq`.
 */
internal class TmdbWatchProviderSource(
    private val registry: MetadataProviderRegistry,
    private val locale: MetadataLocale,
    private val dispatchers: AppDispatchers,
) : WatchProviderSource {

    override val region: String get() = locale.region

    override suspend fun fetchFlatrateIds(mediaId: MediaId): Result<Set<String>> = withContext(dispatchers.io) {
        runCatching { registry.forId(mediaId).watchProviders(mediaId, locale.region).flatrate.map { it.id }.toSet() }
    }
}
