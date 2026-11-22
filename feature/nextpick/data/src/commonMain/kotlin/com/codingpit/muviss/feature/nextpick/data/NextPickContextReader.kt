package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.nextpick.domain.NextPickSettings
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.nextpick.domain.PickContextSource
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.first

/**
 * What a session needs to know before its first question (EPIC 51, `PickContext`). Online is read from
 * the platform monitor, the way Triage and Discover do, so the session can decide offline before asking
 * anything. The library answers "is there a backlog" and "is there anything to rewatch".
 *
 * The anchor is not read here: it is the title a Completion just finished, which only the ViewModel knows
 * (the session is opened from it), so the ViewModel sets `anchorCandidate` on the context it is given.
 */
internal class NextPickContextReader(
    private val connectivity: ConnectivityMonitor,
    private val collection: () -> CollectionApi,
    private val settings: NextPickSettings,
) : PickContextSource {

    override suspend fun read(): PickContext {
        val summaries = collection().observeSummaries().first()
        return PickContext(
            length = settings.length,
            online = connectivity.isOnline.first(),
            hasBacklog = summaries.any { it.status == WatchStatus.NOT_STARTED },
            hasWatchedTitles = summaries.any { it.status == WatchStatus.WATCHED || it.status == WatchStatus.FINISHED },
        )
    }
}
