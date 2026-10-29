package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.domain.CandidateSource
import com.codingpit.muviss.feature.nextpick.domain.PickCandidate
import com.codingpit.muviss.feature.nextpick.domain.PickCandidates
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.nextpick.domain.PickHistory
import com.codingpit.muviss.feature.nextpick.domain.PickHistoryReader
import com.codingpit.muviss.models.MediaSummary
import kotlinx.coroutines.flow.first

/**
 * The library's own titles as pick candidates (EPIC 51, ADR 0032). Nothing here reaches the network, so
 * it answers offline. The engine filters by status and by Muted Genres; this adapter only narrows by
 * the format the person asked for, which the engine does not look at.
 *
 * The library stores genre *names*, not TMDB ids, so these candidates carry no genre ids. Mood matching,
 * genre affinity and Muted Genres therefore do not reach library titles yet; see the follow-up issue
 * filed with #400.
 */
internal class CollectionBacklogCandidates(private val collection: () -> CollectionApi) : PickCandidates {

    override suspend fun gather(criteria: PickCriteria, context: PickContext): List<PickCandidate> {
        if (criteria.source == PickSource.SOMETHING_NEW) return emptyList()
        return collection().observeSummaries().first()
            .filter { criteria.type == null || it.mediaId.type == criteria.type }
            .map { it.toCandidate() }
    }

    private fun CollectionSummary.toCandidate(): PickCandidate = PickCandidate(
        summary = MediaSummary(id = mediaId, title = title, posterUrl = posterUrl),
        source = CandidateSource.BACKLOG,
        libraryStatus = status,
        runtimeMinutes = runtimeMinutes,
        favorite = favorite,
        userRating = rating,
        addedAtEpochMs = addedAtEpochMs.takeIf { it > 0 },
    )
}

/**
 * The library's own history as the ranker reads it. Only the day is used: genre averages need genre ids
 * (see [CollectionBacklogCandidates]), so [PickHistory.genreRatings] stays empty until they exist.
 */
internal class CollectionHistoryReader(private val clock: AppClock) : PickHistoryReader {
    override suspend fun read(criteria: PickCriteria): PickHistory = PickHistory(
        todayEpochDay = clock.nowEpochMs().floorDiv(MS_PER_DAY),
    )

    private companion object {
        const val MS_PER_DAY = 86_400_000L
    }
}
