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
    /** The look-and-feel and triage preferences (#392). Defaulted like every field since v1; older builds ignore it. */
    val settings: SettingsExport? = null,
    /**
     * The Taste Profile (EPIC 51, #402): what the person likes. Defaulted, so a file
     * from before it existed has none. Deliberately NOT a format-version bump: an
     * older build reads its unknown key as nothing (`ignoreUnknownKeys`), whereas a
     * bumped version would make it refuse the whole file as "newer". Live items only;
     * a removed item is simply absent, as a removed snooze is.
     */
    val tasteProfile: List<TasteProfileItemExport> = emptyList(),
    /**
     * The Muted Genres choice marker (ADR 0036, #642): the default set in force when the person chose,
     * and the stamp of that choice. Null when the person has not chosen. Defaulted, so an older file has none.
     */
    val mutedGenreChoice: MutedGenreChoiceExport? = null,
    /**
     * Muted Genres decisions (ADR 0036, #642), tombstones included: a tombstone is an explicit unmute, which
     * a backup must keep. Defaulted, so an older file has none.
     */
    val mutedGenres: List<MutedGenreExport> = emptyList(),
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

/** One Taste Profile item (EPIC 51, #402). [id] is the derived `kind:value` key the row is stored under. */
@Serializable
data class TasteProfileItemExport(
    val id: String,
    val kind: String,
    val value: String,
    val label: String,
    val updatedAtEpochMs: Long,
)

@Serializable
data class ProfileExport(
    val displayName: String,
    val avatarId: String,
)

/**
 * The Display Preferences (ADR 0036): how the person likes the app to look, move and read. Left out on
 * purpose: notifications, automatic sync, crash reports, device backup, and the one-time hints and
 * onboarding flags, which are about this install. Stored as `appSettings` stores them, so a value a
 * newer build wrote is tolerated on read exactly as it is there.
 *
 * [displayPreferencesUpdatedAtEpochMs] is the one stamp of the whole group (#642). Null means the group
 * has never been changed on the exporting device, which is also what a v2 file from before this field
 * means. A restore takes the group as a unit when this stamp is newer than the local one.
 */
@Serializable
data class SettingsExport(
    val theme: String,
    val language: String,
    val region: String,
    val animationsEnabled: Boolean,
    val triageDeckAnimations: Boolean,
    val tickAnimations: Boolean,
    val triageControlScheme: String,
    val triageSnoozePeriod: String,
    val triageSnoozePlacement: String,
    /** The legacy `;`-joined list (ADR 0031). Still written, so an older build can read the file. */
    val mutedGenres: String,
    val mutedGenresInSearch: Boolean,
    /** The group's stamp (#642, ADR 0036). Defaulted: a file from before it existed has none. */
    val displayPreferencesUpdatedAtEpochMs: Long? = null,
)

/** The Muted Genres choice marker (ADR 0036, #642): [defaultsAtChoice] is in `MutedGenresCodec`'s format. */
@Serializable
data class MutedGenreChoiceExport(
    val defaultsAtChoice: String,
    val updatedAtEpochMs: Long,
)

/** One Muted Genres decision (ADR 0036, #642). A [deleted] decision is an explicit unmute. */
@Serializable
data class MutedGenreExport(
    val mediaType: String,
    val genreId: Int,
    val name: String,
    val deleted: Boolean = false,
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
    /** v2; whether a CaughtUp's ticks were written (ADR 0010). A v1 decision restores as resolved. */
    val resolved: Boolean = true,
)
