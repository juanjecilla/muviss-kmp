package com.codingpit.muviss.feature.collection.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.feature.collection.domain.MediaSnapshotSource
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.withContext

/** Routes snapshot refreshes to the right [MetadataProvider][com.codingpit.muviss.core.network.MetadataProvider] via the registry. */
class RegistryMediaSnapshotSource(
    private val registry: MetadataProviderRegistry,
    private val dispatchers: AppDispatchers,
) : MediaSnapshotSource {
    override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> = withContext(dispatchers.io) {
        runCatching { registry.forId(mediaId).details(mediaId) }
    }
}
