package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season

/**
 * Domain-owned seam for fetching a show's season/episode structure — needed
 * to compute "next unseen episode" for watch-next, since progress only
 * stores which ids are ticked, not what episodes exist. The data layer
 * implements this by delegating to
 * [MetadataProviderRegistry][com.codingpit.muviss.core.network.MetadataProviderRegistry],
 * keeping this layer free of a direct dependency on `:core:network` (mirrors
 * collection's `MediaSnapshotSource`).
 */
interface EpisodeCatalogSource {
    suspend fun fetch(mediaId: MediaId): Result<List<Season>>
}
