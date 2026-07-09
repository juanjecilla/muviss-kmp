package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId

/**
 * Domain-owned seam for re-fetching a saved title's metadata from its source.
 * The data layer implements this by delegating to
 * [MetadataProviderRegistry][com.codingpit.muviss.core.network.MetadataProviderRegistry],
 * keeping the domain layer free of a direct dependency on `:core:network`.
 */
interface MediaSnapshotSource {
    suspend fun fetch(mediaId: MediaId): Result<MediaDetails>
}
