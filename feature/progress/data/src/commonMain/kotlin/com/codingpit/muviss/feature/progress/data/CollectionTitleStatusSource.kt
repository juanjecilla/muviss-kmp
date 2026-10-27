package com.codingpit.muviss.feature.progress.data

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.progress.domain.TitleStatusSource
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.first

/**
 * A title's status as the Library derives it, from `CollectionApi.observeSummaries()` (the one joined
 * query that carries the seen count). [collectionApi] is resolved per call: `DefaultCollectionApi` may
 * well grow a progress dependency again, and resolving it while this graph is built is the cycle
 * CLAUDE.md warns about.
 */
internal class CollectionTitleStatusSource(private val collectionApi: () -> CollectionApi) : TitleStatusSource {
    override suspend fun statusOf(mediaId: MediaId): WatchStatus? = collectionApi().observeSummaries().first().firstOrNull { it.mediaId == mediaId }?.status
}
