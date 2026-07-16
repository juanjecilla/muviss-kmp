package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.feature.settings.domain.ImportMediaDetailsSource
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.withContext

/** Routes an import row's details fetch to the right provider via the registry (mirrors collection:data's `RegistryMediaSnapshotSource`). */
class RegistryImportMediaDetailsSource(
    private val registry: MetadataProviderRegistry,
    private val dispatchers: AppDispatchers,
) : ImportMediaDetailsSource {
    override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> = withContext(dispatchers.io) {
        runCatching { registry.forId(mediaId).details(mediaId) }
    }
}
