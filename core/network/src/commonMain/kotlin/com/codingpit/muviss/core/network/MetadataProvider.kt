package com.codingpit.muviss.core.network

import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.SourceId

/**
 * The extensibility seam for external media sources. TMDB is the only
 * implementation today ([com.codingpit.muviss.core.network.tmdb.TmdbProvider]);
 * TVmaze/Trakt/etc. plug in later by implementing this interface and registering
 * with the [MetadataProviderRegistry] — no change to the domain or UI.
 */
interface MetadataProvider {
    val source: SourceId

    suspend fun search(query: String, page: Int = 1): List<MediaSummary>

    suspend fun trending(): List<MediaSummary>

    suspend fun details(id: MediaId): MediaDetails
}

/**
 * Resolves a [MetadataProvider] for a given [SourceId] so callers can work with
 * any source uniformly. A [MediaId] carries its own source, so lookups route to
 * the right provider automatically.
 */
class MetadataProviderRegistry(providers: Iterable<MetadataProvider>) {
    private val bySource: Map<SourceId, MetadataProvider> = providers.associateBy { it.source }

    fun require(source: SourceId): MetadataProvider = bySource[source] ?: error("No MetadataProvider registered for source '$source'")

    fun forId(id: MediaId): MetadataProvider = require(id.source)

    val all: Collection<MetadataProvider> get() = bySource.values
}
