package com.codingpit.muviss.feature.progress.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogSource
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.withContext

/** Routes season/episode catalog fetches to the right provider via the registry (mirrors collection's `RegistryMediaSnapshotSource`). */
class RegistryEpisodeCatalogSource(
    private val registry: MetadataProviderRegistry,
    private val dispatchers: AppDispatchers,
) : EpisodeCatalogSource {
    override suspend fun fetch(mediaId: MediaId): Result<List<Season>> = withContext(dispatchers.io) {
        runCatching { registry.forId(mediaId).details(mediaId).seasons }
    }
}
