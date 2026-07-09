package com.codingpit.muviss.feature.collection.domain

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
