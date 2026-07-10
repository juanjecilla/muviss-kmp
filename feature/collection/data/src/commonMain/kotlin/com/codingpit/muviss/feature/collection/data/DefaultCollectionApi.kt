package com.codingpit.muviss.feature.collection.data

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.domain.AddToCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.ObserveCollectionEntryUseCase
import com.codingpit.muviss.feature.collection.domain.ObserveCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.RemoveFromCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.ToggleFavoriteUseCase
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Bridges the collection feature's use cases to its public [CollectionApi]. */
internal class DefaultCollectionApi(
    private val observeEntry: ObserveCollectionEntryUseCase,
    private val observeCollection: ObserveCollectionUseCase,
    private val addToCollection: AddToCollectionUseCase,
    private val removeFromCollection: RemoveFromCollectionUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
) : CollectionApi {

    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = observeEntry(mediaId)
        .map { entry -> entry?.let { CollectionMembership(it.mediaId, it.favorite) } }

    override fun observeSummaries(): Flow<List<CollectionSummary>> = observeCollection()
        .map { entries ->
            entries.map {
                CollectionSummary(
                    mediaId = it.mediaId,
                    title = it.title,
                    posterUrl = it.posterUrl,
                    status = it.status,
                    genres = it.genres,
                    runtimeMinutes = it.runtimeMinutes,
                    seenEpisodes = it.seenEpisodes,
                )
            }
        }

    override suspend fun add(details: MediaDetails) = addToCollection(details)

    override suspend fun remove(mediaId: MediaId) = removeFromCollection(mediaId)

    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = toggleFavorite(mediaId, favorite)
}
