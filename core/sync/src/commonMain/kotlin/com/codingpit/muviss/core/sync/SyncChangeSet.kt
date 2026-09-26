package com.codingpit.muviss.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One change-log slice covering every table [SyncEngine] replicates —
 * mirrors `collectionEntry` / `episodeProgress` / `mediaList` / `listEntry` /
 * `triageDecision` / `triageSnooze` / `episodePlay`
 * (see `core/database`'s `.sq` files) minus device-local-only columns.
 * [SyncBackend.push] sends a set of local dirty rows; [SyncBackend.pull]
 * delivers remote rows as pages, each a set holding one table's rows. `@Serializable`
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
    val triageSnoozes: List<TriageSnoozeChange> = emptyList(),
    val episodePlays: List<EpisodePlayChange> = emptyList(),
) {
    val isEmpty: Boolean
        get() = collectionEntries.isEmpty() && episodeProgress.isEmpty() && mediaLists.isEmpty() &&
            listEntries.isEmpty() && triageDecisions.isEmpty() && triageSnoozes.isEmpty() && episodePlays.isEmpty()

    val size: Int
        get() = collectionEntries.size + episodeProgress.size + mediaLists.size + listEntries.size +
            triageDecisions.size + triageSnoozes.size + episodePlays.size
}

/**
 * Mirrors `collectionEntry` (`CollectionEntry.sq`). [notificationsMuted] is
 * deliberately absent — a per-device notification preference, not user
 * library data, so it never leaves the device (see ADR 0009).
 *
 * [revisitWillingness] and [coWatchPinned] are present for the opposite reason
 * (EPIC 41, ADR 0022): both are user-authored, like [favorite]/[rating]/[note],
 * so they belong to the library rather than to a device. [revisitWillingness]
 * is nullable and its null is meaningful — "never answered", as distinct from
 * "no" — which is exactly the case `explicitNulls = true` exists for
 * (`SupabasePostgrestClient`, ADR 0020): a key left out of a merge-duplicates
 * upsert is not written, so clearing an answer would never reach the server.
 *
 * They carry defaults only so a pulled row from a server that predates the
 * column decodes; a locally built change always passes them.
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
    @SerialName("revisit_willingness") val revisitWillingness: Boolean? = null,
    @SerialName("cowatch_pinned") val coWatchPinned: Boolean = false,
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

/**
 * Mirrors `triageSnooze` (`TriageSnooze.sq`, ADR 0023). Postponing a title is
 * user intent for the same reason skipping one is, and replicates for the same
 * reason: being asked again on the desktop about something already postponed
 * on the phone is the failure, not the feature.
 *
 * Deliberately a separate change type rather than a `verdict` value on
 * [TriageDecisionChange]: a build that predates this table simply never asks
 * for it, whereas an unknown verdict on a table it *does* pull would be
 * counted as decided and dropped from every read at once.
 *
 * The card snapshot ([title]/[year]/[posterUrl]/[overview]) rides along
 * because the receiving device has to render the card when it comes due and
 * cannot cheaply re-derive it — see `TriageSnooze`.
 */
@Serializable
data class TriageSnoozeChange(
    @SerialName("media_id") val mediaId: String,
    @SerialName("media_type") val mediaType: String,
    val title: String,
    val year: Long?,
    @SerialName("poster_url") val posterUrl: String?,
    val overview: String?,
    @SerialName("snoozed_at_epoch_ms") val snoozedAtEpochMs: Long,
    @SerialName("due_at_epoch_day") val dueAtEpochDay: Long,
    @SerialName("updated_at_epoch_ms") val updatedAtEpochMs: Long,
    val deleted: Boolean,
)
