package com.codingpit.muviss.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One change-log slice covering every table [SyncEngine] replicates —
 * mirrors `collectionEntry` / `episodeProgress` / `mediaList` / `listEntry`
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
) {
    val isEmpty: Boolean
        get() = collectionEntries.isEmpty() && episodeProgress.isEmpty() && mediaLists.isEmpty() && listEntries.isEmpty()

    val size: Int
        get() = collectionEntries.size + episodeProgress.size + mediaLists.size + listEntries.size
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
