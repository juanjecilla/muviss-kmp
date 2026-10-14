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

    /**
     * The saved library snapshot of [mediaId] as [MediaDetails], or null when it
     * is not in the library. Offline-first Detail (EPIC 30, #73) renders this
     * before the network answers. It carries no overview and no seasons: the
     * snapshot does not store them (seasons come from `ProgressApi.storedSeasons`).
     */
    suspend fun savedDetails(mediaId: MediaId): MediaDetails? = null

    /** Soft-deletes the entry; a no-op if it was never saved. */
    suspend fun remove(mediaId: MediaId)

    suspend fun setFavorite(mediaId: MediaId, favorite: Boolean)

    /** Per-show opt-out from EPIC 5's new-episode notifications, independent of the global toggle in Settings. */
    suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean)

    /** Sets or clears (via null) the personal 1-10 rating (EPIC 15); throws [IllegalArgumentException] for anything outside 1-10. */
    suspend fun setRating(mediaId: MediaId, rating: Int?)

    /** Sets or clears (via null, or a blank string) the personal free-text note (EPIC 15). */
    suspend fun setNote(mediaId: MediaId, note: String?)

    /**
     * Records whether the user would watch an already-seen title again with
     * someone (EPIC 41, ADR 0022). Null clears the answer back to "never
     * asked", which is why it is nullable rather than a plain toggle.
     */
    suspend fun setRevisitWillingness(mediaId: MediaId, willing: Boolean?)

    /** Marks a title as one this user is actively pushing for in a Shortlist (EPIC 41). */
    suspend fun setCoWatchPinned(mediaId: MediaId, pinned: Boolean)

    /**
     * Re-fetches metadata for every saved title (the same refresh the
     * Collection screen runs on pull-to-refresh) and returns the ones whose
     * aired-episode count went up since the previous snapshot, excluding any
     * show the user muted individually. Driven by `:app:androidApp`'s
     * background worker (EPIC 5); the caller still has to check the global
     * notifications toggle itself before deciding whether to post anything.
     */
    suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult>

    /**
     * Re-fetches metadata for just [mediaIds] and refreshes each snapshot —
     * the bounded counterpart to what pull-to-refresh does for the whole
     * library. `:core:sync` calls this after a pull through its own seam
     * (`TitleRefresher`), for exactly the titles that pull touched, so a
     * title synced from another device is not stuck showing that device's
     * snapshot until the Library is next opened (issue #101).
     *
     * Defaulted to a no-op rather than added as a plain abstract member: over
     * a dozen fakes across other features implement [CollectionApi] for
     * their own tests and have no reason to care about this one, sync-only
     * corner of it.
     */
    suspend fun refreshTitles(mediaIds: Set<MediaId>) = Unit
}

/**
 * The minimal membership info a peer needs to render add/remove + favorite +
 * mute + rating + note controls. [rating] and [note] are EPIC 15 additions
 * (search:ui's `DetailScreen` is the only consumer of either today).
 */
data class CollectionMembership(
    val mediaId: MediaId,
    val favorite: Boolean,
    val notificationsMuted: Boolean = false,
    val rating: Int? = null,
    val note: String? = null,
    /**
     * Revisit Willingness (EPIC 41, ADR 0022). Null is "never answered", which
     * is distinct from `false`: unanswered falls back to the per-device
     * co-watch default, answered does not.
     */
    val revisitWillingness: Boolean? = null,
    val coWatchPinned: Boolean = false,
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
 *
 * [rating] was added for EPIC 15 (ratings & notes): the collection feature's
 * own list uses it for the rating badge/sort, and the profile feature's
 * `ProfileStatsCalculator` uses it for the average-rating/top-rated-genre
 * stats. [note] is deliberately not exposed here — no peer needs it at this
 * granularity today (it is only rendered/edited in search:ui's
 * `DetailScreen`, via [CollectionMembership.note]).
 *
 * [favorite] and [addedAtEpochMs] were added for EPIC 16 (recommendations):
 * search:ui's `ForYouSeeding` reads them to pick which saved titles seed the
 * Discover tab's "For you" section (favorites and top-rated titles, most
 * recently added first).
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
    val rating: Int? = null,
    val favorite: Boolean = false,
    val addedAtEpochMs: Long = 0,
    /**
     * Read by co-watch (EPIC 41) to build a Watch Pool. Defaulted like the rest,
     * so peers that do not care carry on ignoring them.
     */
    val revisitWillingness: Boolean? = null,
    val coWatchPinned: Boolean = false,
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
