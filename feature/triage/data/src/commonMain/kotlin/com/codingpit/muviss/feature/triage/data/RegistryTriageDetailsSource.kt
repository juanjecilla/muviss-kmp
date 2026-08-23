package com.codingpit.muviss.feature.triage.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.feature.triage.domain.TriageDetailsSource
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.withContext

/**
 * Fetches the full season/episode structure a saving verdict needs. For TV
 * this is several sequential requests (see `TmdbProvider.details`), which is
 * exactly why the deck never waits on it — see `RecordDecisionUseCase`.
 */
class RegistryTriageDetailsSource(
    private val registry: MetadataProviderRegistry,
    private val dispatchers: AppDispatchers,
) : TriageDetailsSource {
    override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> = withContext(dispatchers.io) {
        runCatching { registry.forId(mediaId).details(mediaId) }
    }
}
