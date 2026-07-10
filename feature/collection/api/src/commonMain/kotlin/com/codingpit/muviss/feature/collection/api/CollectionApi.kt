package com.codingpit.muviss.feature.collection.api

import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.Flow

/**
 * Public contract of the collection feature. Peers (e.g. search's
 * `DetailScreen`, progress's watch-next) depend on this module only — never
 * collection's domain/data/ui — to add, remove, and observe library
 * membership without knowing anything about how the library is stored.
 */
interface CollectionApi {
    /** Emits the current membership for [mediaId], or null while it is not saved. */
    fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?>

    /**
     * Every saved title's derived [WatchStatus] plus the display fields a
     * peer needs to render its own list (e.g. progress's watch-next filters
     * this to [WatchStatus.WATCHING]).
     */
    fun observeSummaries(): Flow<List<CollectionSummary>>

    /** Saves [details] to the library, or refreshes its snapshot if already saved. */
    suspend fun add(details: MediaDetails)

    /** Soft-deletes the entry; a no-op if it was never saved. */
    suspend fun remove(mediaId: MediaId)

    suspend fun setFavorite(mediaId: MediaId, favorite: Boolean)

    /** Per-show opt-out from EPIC 5's new-episode notifications, independent of the global toggle in Settings. */
    suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean)

    /**
     * Re-fetches metadata for every saved title (the same refresh the
     * Collection screen runs on pull-to-refresh) and returns the ones whose
     * aired-episode count went up since the previous snapshot, excluding any
     * show the user muted individually. Driven by `:app:androidApp`'s
     * background worker (EPIC 5); the caller still has to check the global
     * notifications toggle itself before deciding whether to post anything.
     */
    suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult>
}

/** The minimal membership info a peer needs to render add/remove + favorite + mute controls. */
data class CollectionMembership(
    val mediaId: MediaId,
    val favorite: Boolean,
    val notificationsMuted: Boolean = false,
)

/**
 * The minimal display + status info a peer needs to render its own view of
 * the library. [genres], [runtimeMinutes] and [seenEpisodes] exist for the
 * profile feature's stats aggregation (genre breakdown, hours-watched
 * estimate); [notificationsMuted] mirrors [CollectionMembership]'s so a peer
 * could render a mute indicator in a list, same as it can [favorite] — the
 * EPIC 5 background worker doesn't need it here, since
 * [CollectionApi.refreshAndFindNewEpisodes] already excludes muted shows
 * itself. Other peers simply ignore whichever of these they don't need.
 */
data class CollectionSummary(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val status: WatchStatus,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val seenEpisodes: Int = 0,
    val notificationsMuted: Boolean = false,
)

/**
 * One saved show/movie with new episodes since the last refresh, as reported
 * by [CollectionApi.refreshAndFindNewEpisodes]. [latestEpisodeLabel] is the
 * "SxxExx" label of the newest aired episode, or null for a movie (whose
 * notification text is just "<title> is out").
 */
data class NewEpisodesResult(
    val mediaId: MediaId,
    val title: String,
    val newEpisodeCount: Int,
    val latestEpisodeLabel: String?,
)
