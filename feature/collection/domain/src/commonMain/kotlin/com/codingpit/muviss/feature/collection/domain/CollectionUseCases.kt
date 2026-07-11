package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Observes the full saved library, newest first. */
class ObserveCollectionUseCase(private val repository: CollectionRepository) {
    operator fun invoke(): Flow<List<CollectionEntry>> = repository.observeAll()
}

/** Observes one title's membership, e.g. to drive an add/remove + favorite control. */
class ObserveCollectionEntryUseCase(private val repository: CollectionRepository) {
    operator fun invoke(mediaId: MediaId): Flow<CollectionEntry?> = repository.observeEntry(mediaId)
}

/** Saves a title to the library (or refreshes its snapshot if already saved). */
class AddToCollectionUseCase(private val repository: CollectionRepository) {
    suspend operator fun invoke(details: MediaDetails) = repository.upsertSnapshot(details)
}

/** Removes (soft-deletes) a title from the library. */
class RemoveFromCollectionUseCase(private val repository: CollectionRepository) {
    suspend operator fun invoke(mediaId: MediaId) = repository.remove(mediaId)
}

/** Toggles the favorite flag, independent of watch status (ADR 0005). */
class ToggleFavoriteUseCase(private val repository: CollectionRepository) {
    suspend operator fun invoke(mediaId: MediaId, favorite: Boolean) = repository.setFavorite(mediaId, favorite)
}

/** Toggles a single show's new-episode notification mute (EPIC 5), independent of the global settings toggle. */
class ToggleNotificationsMutedUseCase(private val repository: CollectionRepository) {
    suspend operator fun invoke(mediaId: MediaId, muted: Boolean) = repository.setNotificationsMuted(mediaId, muted)
}

/**
 * Sets or clears (via null) the personal 1-10 rating (EPIC 15). The only
 * place the 1-10 range is enforced — [CollectionRepository]/the database
 * column accept any integer, this use case is the gate.
 */
class SetRatingUseCase(private val repository: CollectionRepository) {
    suspend operator fun invoke(mediaId: MediaId, rating: Int?) {
        require(rating == null || rating in RATING_RANGE) { "rating must be null or within $RATING_RANGE, was $rating" }
        repository.setRating(mediaId, rating)
    }

    private companion object {
        val RATING_RANGE = 1..10
    }
}

/** Sets or clears (via null, or a blank string) the personal free-text note (EPIC 15). */
class SetNoteUseCase(private val repository: CollectionRepository) {
    suspend operator fun invoke(mediaId: MediaId, note: String?) = repository.setNote(mediaId, note?.trim()?.ifBlank { null })
}

/**
 * Groups the collection feature's per-show toggle/setter use cases
 * (favorite, mute, rating, note) so
 * [DefaultCollectionApi][com.codingpit.muviss.feature.collection.data.DefaultCollectionApi]'s
 * constructor doesn't grow one parameter per toggle — the same pattern
 * `feature/settings/domain`'s `SettingsActions` uses for that feature's
 * mutators.
 */
class CollectionToggles(
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val toggleNotificationsMutedUseCase: ToggleNotificationsMutedUseCase,
    private val setRatingUseCase: SetRatingUseCase,
    private val setNoteUseCase: SetNoteUseCase,
) {
    suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = toggleFavoriteUseCase(mediaId, favorite)
    suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = toggleNotificationsMutedUseCase(mediaId, muted)
    suspend fun setRating(mediaId: MediaId, rating: Int?) = setRatingUseCase(mediaId, rating)
    suspend fun setNote(mediaId: MediaId, note: String?) = setNoteUseCase(mediaId, note)
}

/**
 * Re-fetches metadata for every saved, non-deleted title and upserts the
 * refreshed snapshot (aired-episode count, production status, poster). Meant
 * to run on Collection screen entry; best-effort per title — one failure
 * doesn't block the rest.
 */
class RefreshCollectionSnapshotsUseCase(
    private val repository: CollectionRepository,
    private val snapshotSource: MediaSnapshotSource,
) {
    suspend operator fun invoke() {
        repository.observeAll().first().forEach { entry ->
            snapshotSource.fetch(entry.mediaId).onSuccess { details -> repository.upsertSnapshot(details) }
        }
    }
}

/**
 * The refresh path EPIC 5's Android background worker drives: re-fetches
 * every saved title exactly like [RefreshCollectionSnapshotsUseCase] (and
 * upserts the same way, so the Collection screen reflects the new snapshot
 * too), but also runs [NewEpisodesCalculator] over the before/after state so
 * the caller learns which shows got new episodes. Muted shows
 * ([CollectionEntry.notificationsMuted]) are excluded here — the global
 * settings toggle is a separate concern the worker checks before calling
 * this at all.
 */
class RefreshAndFindNewEpisodesUseCase(
    private val repository: CollectionRepository,
    private val snapshotSource: MediaSnapshotSource,
    private val clock: AppClock,
) {
    suspend operator fun invoke(): List<NewEpisodeNotification> {
        val before = repository.observeAll().first()
        val refreshed = before.mapNotNull { entry ->
            snapshotSource.fetch(entry.mediaId).getOrNull()?.also { repository.upsertSnapshot(it) }
        }
        val mutedMediaIds = before.filter { it.notificationsMuted }.map { it.mediaId }.toSet()
        return NewEpisodesCalculator.diff(before, refreshed, mutedMediaIds, clock.todayEpochDay())
    }
}
