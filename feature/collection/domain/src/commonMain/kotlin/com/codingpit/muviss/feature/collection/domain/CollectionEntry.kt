package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.core.model.CountedProgress
import com.codingpit.muviss.core.model.SpecialsCounting
import com.codingpit.muviss.core.model.WatchProgress
import com.codingpit.muviss.core.model.WatchStatusCalculator
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.WatchStatus

/**
 * A title the user has saved, together with the denormalized snapshot taken
 * at save time (see CONTEXT.md "Snapshot"). [mediaType] is read off [mediaId]
 * rather than duplicated.
 *
 * [status] is always derived via [WatchStatusCalculator] — this feature never
 * stores it (ADR 0005). [seenEpisodes] is fed in from the progress feature
 * (via its `:api`, see `SqlDelightCollectionRepository`) rather than stored
 * here; it defaults to 0 so a bare [CollectionEntry] (e.g. in tests) still
 * derives [WatchStatus.NOT_STARTED].
 *
 * [genres] and [runtimeMinutes] were added for EPIC 4 (profile stats):
 * genres straight from TMDB, and [runtimeMinutes] the movie's runtime or a TV
 * show's average per-episode runtime (null when TMDB didn't report one).
 *
 * [notificationsMuted] was added for EPIC 5 (new-episode notifications): an
 * independent per-show opt-out from the Android background worker's
 * new-episode alerts, checked alongside (not instead of) the global toggle
 * in `feature/settings` (see [NewEpisodesCalculator]).
 *
 * [rating] and [note] were added for EPIC 15 (ratings & notes): personal,
 * local-only user data exactly like [favorite] (ADR 0005 — never derived,
 * safe to store), preserved across a snapshot refresh the same way. [rating]
 * is 1-10 or null for "unrated"; range validation lives in [SetRatingUseCase],
 * not here.
 */
data class CollectionEntry(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val releaseYear: Int?,
    val productionStatus: ProductionStatus,
    val totalEpisodes: Int,
    val airedEpisodes: Int,
    val favorite: Boolean,
    val addedAtEpochMs: Long,
    val seenEpisodes: Int = 0,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val notificationsMuted: Boolean = false,
    val rating: Int? = null,
    val note: String? = null,
    /** EPIC 41 (ADR 0022). Null is "never answered", not "no". */
    val revisitWillingness: Boolean? = null,
    val coWatchPinned: Boolean = false,
    /** Ticked specials that are also in the cached aired catalog, read at read time (#938). Zero when the setting is off. */
    val specialsSeen: Int = 0,
    /** Cached aired specials for this title (#938). Read at read time, never stored in the snapshot. */
    val specialsAired: Int = 0,
    /** The per-device "Count specials toward progress" setting (#938, ADR 0037), as the read saw it. */
    val countSpecials: Boolean = false,
) {
    val mediaType: MediaType get() = mediaId.type

    /** The seen and aired counts status and the Library's progress read, with specials counted only by choice (#938). */
    val counted: CountedProgress
        get() = SpecialsCounting.count(
            regularSeen = seenEpisodes,
            regularAired = airedEpisodes,
            specialsSeen = specialsSeen,
            specialsAired = specialsAired,
            countSpecials = countSpecials,
        )

    /** Episodes seen, as status counts them (#938). Use this, not [seenEpisodes], wherever progress is shown. */
    val countedSeen: Int get() = counted.seen

    /** Episodes aired, as status counts them (#938). Use this, not [airedEpisodes], wherever progress is shown. */
    val countedAired: Int get() = counted.aired

    val status: WatchStatus
        get() = WatchStatusCalculator.derive(
            WatchProgress(
                mediaType = mediaType,
                seenEpisodes = counted.seen,
                airedEpisodes = counted.aired,
                totalEpisodes = totalEpisodes,
                productionStatus = productionStatus,
                favorite = favorite,
            ),
        )
}
