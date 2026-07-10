package com.codingpit.muviss.feature.settings.data

import kotlinx.serialization.Serializable

/**
 * Wire shape of the data-export JSON: a straight, denormalized dump of the
 * `collectionEntry` + `episodeProgress` tables (see `SqlDelightSettingsRepository.exportData`).
 * Import is explicitly out of scope for EPIC 8 — these types only need to
 * serialize, not round-trip.
 */
@Serializable
data class MuvissDataExport(
    val exportedAtEpochMs: Long,
    val collection: List<CollectionEntryExport>,
    val progress: List<EpisodeProgressExport>,
)

@Serializable
data class CollectionEntryExport(
    val mediaId: String,
    val mediaType: String,
    val title: String,
    val posterUrl: String?,
    val releaseYear: Int?,
    val productionStatus: String,
    val totalEpisodes: Int,
    val airedEpisodes: Int,
    val favorite: Boolean,
    val addedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Serializable
data class EpisodeProgressExport(
    val episodeId: String,
    val mediaId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val seen: Boolean,
    val updatedAtEpochMs: Long,
)
