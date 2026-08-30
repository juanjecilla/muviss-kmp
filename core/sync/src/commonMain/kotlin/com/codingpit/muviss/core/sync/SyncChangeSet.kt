package com.codingpit.muviss.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One change-log slice covering every table [SyncEngine] replicates —
 * mirrors `collectionEntry` / `episodeProgress` / `mediaList` / `listEntry` /
 * `triageDecision` / `episodePlay`
 * (see `core/database`'s `.sq` files) minus device-local-only columns.
 * [SyncBackend.push] sends a set of local dirty rows; [SyncBackend.pull]
 * returns a set of remote rows changed since some point in time. `@Serializable`
 * with snake_case [SerialName]s because [SupabaseSyncBackend] sends these
 * directly as PostgREST JSON bodies — see docs/SYNC.md for the table
 * schema each field maps to.
 */
@Serializable
data class SyncChangeSet(
    val collectionEntries: List<CollectionEntryChange> = emptyList(),
    val episodeProgress: List<EpisodeProgressChange> = emptyList(),
    val mediaLists: List<MediaListChange> = emptyList(),
    val listEntries: List<ListEntryChange> = emptyList(),
    val triageDecisions: List<TriageDecisionChange> = emptyList(),
    val episodePlays: List<EpisodePlayChange> = emptyList(),
) {
    val isEmpty: Boolean
        get() = collectionEntries.isEmpty() && episodeProgress.isEmpty() && mediaLists.isEmpty() &&
            listEntries.isEmpty() && triageDecisions.isEmpty() && episodePlays.isEmpty()

    val size: Int
        get() = collectionEntries.size + episodeProgress.size + mediaLists.size + listEntries.size +
            triageDecisions.size + episodePlays.size

    /**
     * The newest `updated_at_epoch_ms` in this set, or null when it is empty.
     *
     * This is what [SyncEngine] advances its pull cursor to, rather than its
     * own `AppClock`. Rows are stamped by whichever *device* wrote them, so a
     * cursor taken from the reading device's clock is comparing two unrelated
     * clocks: if the writing device runs even slightly ahead, its rows land
     * with timestamps above the reader's "now", the next `gt.<cursor>` filter
     * excludes them, and they are never pulled again. Advancing to a
     * timestamp that actually came off the server has no such gap.
     */
    val maxUpdatedAtEpochMs: Long?
        get() = listOf(
            collectionEntries.maxOfOrNull { it.updatedAtEpochMs },
            episodeProgress.maxOfOrNull { it.updatedAtEpochMs },
            mediaLists.maxOfOrNull { it.updatedAtEpochMs },
            listEntries.maxOfOrNull { it.updatedAtEpochMs },
            triageDecisions.maxOfOrNull { it.updatedAtEpochMs },
            episodePlays.maxOfOrNull { it.updatedAtEpochMs },
        ).filterNotNull().maxOrNull()
}

/**
 * Mirrors `collectionEntry` (`CollectionEntry.sq`). [notificationsMuted] is
 * deliberately absent — a per-device notification preference, not user
 * library data, so it never leaves the device (see ADR 0009).
 */
@Serializable
data class CollectionEntryChange(
    @SerialName("media_id") val mediaId: String,
    @SerialName("media_type") val mediaType: String,
    val title: String,
    @SerialName("poster_url") val posterUrl: String?,
    @SerialName("release_year") val releaseYear: Int?,
    @SerialName("production_status") val productionStatus: String,
    @SerialName("total_episodes") val totalEpisodes: Int,
    @SerialName("aired_episodes") val airedEpisodes: Int,
    val favorite: Boolean,
    val genres: String,
    @SerialName("runtime_minutes") val runtimeMinutes: Int?,
    @SerialName("added_at_epoch_ms") val addedAtEpochMs: Long,
    @SerialName("updated_at_epoch_ms") val updatedAtEpochMs: Long,
    val deleted: Boolean,
    val rating: Int?,
    val note: String?,
)

/**
 * Mirrors `episodeProgress` (`EpisodeProgress.sq`). No `deleted`: episode
 * ticks are idempotent booleans, never soft-deleted — un-ticking sets
 * [seen] back to false, that state change is itself what propagates (see
 * ADR 0009).
 */
@Serializable
data class EpisodeProgressChange(
    @SerialName("episode_id") val episodeId: String,
    @SerialName("media_id") val mediaId: String,
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_number") val episodeNumber: Int,
    val seen: Boolean,
    @SerialName("updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

/**
 * Mirrors `episodePlay` (`EpisodePlay.sq`, ADR 0011 as amended by ADR 0013).
 *
 * [id] is carried explicitly, unlike every other change type here whose key is
 * a natural column: a play's identity is derived
 * (`episodeId@watchedAtEpochMs`) precisely so it survives being recorded on a
 * second device, and sending it keeps the derivation in one place rather than
 * having every backend re-implement it.
 */
@Serializable
data class EpisodePlayChange(
    val id: String,
    @SerialName("episode_id") val episodeId: String,
    @SerialName("media_id") val mediaId: String,
    @SerialName("watched_at_epoch_ms") val watchedAtEpochMs: Long,
    @SerialName("updated_at_epoch_ms") val updatedAtEpochMs: Long,
    val deleted: Boolean,
)

/** Mirrors `mediaList` (`MediaList.sq`). */
@Serializable
data class MediaListChange(
    val id: String,
    val name: String,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
    @SerialName("updated_at_epoch_ms") val updatedAtEpochMs: Long,
    val deleted: Boolean,
)

/** Mirrors `listEntry` (`MediaList.sq`). */
@Serializable
data class ListEntryChange(
    @SerialName("list_id") val listId: String,
    @SerialName("media_id") val mediaId: String,
    @SerialName("added_at_epoch_ms") val addedAtEpochMs: Long,
    @SerialName("updated_at_epoch_ms") val updatedAtEpochMs: Long,
    val deleted: Boolean,
)

/**
 * Mirrors `triageDecision` (`TriageDecision.sq`, ADR 0010). Skipping a title
 * is user intent, not a device preference, so unlike
 * `collectionEntry.notificationsMuted` it does replicate: a title ruled on
 * from a phone must not come back around on a desktop, or the promise that
 * triage never asks twice only holds on one device.
 *
 * [resolved] rides along so a verdict whose side effects failed on one device
 * is still visible as incomplete on another.
 */
@Serializable
data class TriageDecisionChange(
    @SerialName("media_id") val mediaId: String,
    @SerialName("media_type") val mediaType: String,
    val verdict: String,
    val title: String,
    @SerialName("poster_url") val posterUrl: String?,
    @SerialName("decided_at_epoch_ms") val decidedAtEpochMs: Long,
    val resolved: Boolean,
    @SerialName("updated_at_epoch_ms") val updatedAtEpochMs: Long,
    val deleted: Boolean,
)
