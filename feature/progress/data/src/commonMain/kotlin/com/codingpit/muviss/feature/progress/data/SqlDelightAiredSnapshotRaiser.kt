package com.codingpit.muviss.feature.progress.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.CollectionEntryQueries
import com.codingpit.muviss.feature.progress.domain.AiredSnapshotRaiser
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.withContext

/** [AiredSnapshotRaiser] over the shared `collectionEntry` table: one guarded UPDATE, a no-op when the snapshot is not behind. */
class SqlDelightAiredSnapshotRaiser(
    private val queries: CollectionEntryQueries,
    private val dispatchers: AppDispatchers,
) : AiredSnapshotRaiser {
    override suspend fun raise(mediaId: MediaId, airedEpisodes: Int) {
        withContext(dispatchers.io) {
            queries.raiseAiredTo(aired = airedEpisodes.toLong(), mediaId = mediaId.toString())
        }
    }
}
