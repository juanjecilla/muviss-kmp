package com.codingpit.muviss.feature.settings.data

import kotlinx.serialization.Serializable

/**
 * Wire shape of the data-export JSON: a straight, denormalized dump of the
 * `collectionEntry` + `episodeProgress` + `triageDecision` tables (see `SqlDelightSettingsRepository.exportData`).
 * Import is explicitly out of scope for EPIC 8 — these types only need to
 * serialize, not round-trip.
 */
@Serializable
data class MuvissDataExport(
    val exportedAtEpochMs: Long,
    val collection: List<CollectionEntryExport>,
    val progress: List<EpisodeProgressExport>,
    /**
     * Triage decisions (ADR 0010). Defaulted so an export produced before this
     * field existed still parses, and so the field can be dropped from a
     * hand-written file without breaking anything.
     */
    val triage: List<TriageDecisionExport> = emptyList(),
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

@Serializable
data class TriageDecisionExport(
    val mediaId: String,
    val mediaType: String,
    val verdict: String,
    val title: String,
    val posterUrl: String?,
    val decidedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
