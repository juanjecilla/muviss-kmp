package com.codingpit.muviss.feature.settings.data

import kotlinx.serialization.Serializable

/**
 * Wire shape of the data-export JSON: a straight, denormalized dump of the
 * `collectionEntry` + `episodeProgress` + `episodePlay` + `triageDecision`
 * tables (see `SqlDelightSettingsRepository.exportData`).
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
    /**
     * Rewatch history (ADR 0011). Defaulted like [triage] so an export
     * produced before play history existed still parses.
     *
     * Exported but never synced: v1 is local-only, and append-only rows would
     * need a different conflict rule than ADR 0009's last-write-wins. A
     * person's own backup is a different question from replication, and
     * leaving their rewatch history out of it would silently lose it on
     * reinstall.
     */
    val plays: List<EpisodePlayExport> = emptyList(),
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
    /**
     * Revisit Willingness and the co-watch pin (EPIC 41, ADR 0022). User-authored,
     * like [favorite], so a backup that dropped them would lose real answers —
     * which is what #72 ("a backup you can restore") is about.
     *
     * Defaulted so an export produced before EPIC 41 still parses. Null on
     * [revisitWillingness] is meaningful rather than missing: it is "never
     * answered", which is also the right thing for an older export to restore as.
     */
    val revisitWillingness: Boolean? = null,
    val coWatchPinned: Boolean = false,
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
data class EpisodePlayExport(
    val episodeId: String,
    val mediaId: String,
    val watchedAtEpochMs: Long,
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
