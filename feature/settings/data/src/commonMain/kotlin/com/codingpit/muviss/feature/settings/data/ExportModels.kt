package com.codingpit.muviss.feature.settings.data

import kotlinx.serialization.Serializable

/**
 * Wire shape of the data-export JSON: a straight, denormalized dump of every
 * table that holds the user's own data (see `SqlDelightSettingsRepository.exportData`).
 *
 * Version 2 (EPIC 29, #72) is the first shape meant to be *restored*, not
 * just read: v1 dropped ratings, notes, genres, runtimes, muted notifications,
 * every custom list, snoozes and the profile. Every field added since v1 is
 * defaulted, so a v1 file (which has no [formatVersion] at all, and so reads
 * as 1) still parses into this same class.
 */
@Serializable
data class MuvissDataExport(
    /** 1 for files written before EPIC 29, which carried no version; [CURRENT_FORMAT_VERSION] since. */
    val formatVersion: Int = 1,
    /** The app that wrote it, e.g. `1.1.0`; null in v1 files. Diagnostic only — import keys on [formatVersion]. */
    val appVersion: String? = null,
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
    /** Snoozed triage cards (ADR 0023). v2. */
    val snoozes: List<TriageSnoozeExport> = emptyList(),
    /** Custom lists (EPIC 17). v2. */
    val lists: List<MediaListExport> = emptyList(),
    /** Membership of [lists]; a title can be listed without being in the library. v2. */
    val listEntries: List<ListEntryExport> = emptyList(),
    /** Display name and avatar. Null in v1 files, and restored only when present. v2. */
    val profile: ProfileExport? = null,
) {
    companion object {
        const val CURRENT_FORMAT_VERSION: Int = 2
    }
}

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
    // v2 (EPIC 29, #72): user-authored or user-visible columns v1 dropped.
    // Defaulted to the column defaults, which is what a v1 file restores as.
    /** Comma-separated, as stored. */
    val genres: String = "",
    val runtimeMinutes: Int? = null,
    val notificationsMuted: Boolean = false,
    val rating: Int? = null,
    val note: String? = null,
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
    /** v2; a v1 play restores with its [watchedAtEpochMs], which is what its stamp was when it was written. */
    val updatedAtEpochMs: Long? = null,
)

@Serializable
data class TriageSnoozeExport(
    val mediaId: String,
    val mediaType: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val overview: String?,
    val snoozedAtEpochMs: Long,
    val dueAtEpochDay: Long,
    val updatedAtEpochMs: Long,
)

@Serializable
data class MediaListExport(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Serializable
data class ListEntryExport(
    val listId: String,
    val mediaId: String,
    val addedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Serializable
data class ProfileExport(
    val displayName: String,
    val avatarId: String,
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
    /** v2; whether a CaughtUp's ticks were written (ADR 0010). A v1 decision restores as resolved. */
    val resolved: Boolean = true,
)
