package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
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
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.first

/**
 * The library's own titles as pick candidates (EPIC 51, ADR 0032). Nothing here reaches the network for
 * the titles themselves, so it answers offline. The engine filters by status and by Muted Genres; this
 * adapter narrows by the format the person asked for, which the engine does not look at, and maps each
 * stored genre name to its TMDB id (#547) so Muted Genres and mood matching reach library titles.
 */
internal class CollectionBacklogCandidates(
    private val collection: () -> CollectionApi,
    private val genreIds: LibraryGenreIds,
) : PickCandidates {

    override suspend fun gather(criteria: PickCriteria, context: PickContext): List<PickCandidate> {
        if (criteria.source == PickSource.SOMETHING_NEW) return emptyList()
        val summaries = collection().observeSummaries().first()
            .filter { criteria.type == null || it.mediaId.type == criteria.type }
        val indexes = summaries.map { it.mediaId.type }.distinct().associateWith { genreIds.index(it, context.online) }
        return summaries.map { it.toCandidate(indexes.getValue(it.mediaId.type)) }
    }

    private fun CollectionSummary.toCandidate(index: GenreNameIndex): PickCandidate = PickCandidate(
        summary = MediaSummary(
            id = mediaId,
            title = title,
            posterUrl = posterUrl,
            genreIds = index.idsOf(genres),
        ),
        source = CandidateSource.BACKLOG,
        libraryStatus = status,
        runtimeMinutes = runtimeMinutes,
        favorite = favorite,
        userRating = rating,
        addedAtEpochMs = addedAtEpochMs.takeIf { it > 0 },
    )
}

/**
 * The library's own history as the ranker reads it: the day, and genre affinity. A genre's rating is the
 * average of the person's ratings of the library titles that carry it, so it reads only what they rated
 * on purpose (ADR 0032). Genre names map to ids as [CollectionBacklogCandidates] maps them (#547).
 */
internal class CollectionHistoryReader(
    private val clock: AppClock,
    private val collection: () -> CollectionApi,
    private val connectivity: ConnectivityMonitor,
    private val genreIds: LibraryGenreIds,
) : PickHistoryReader {

    override suspend fun read(criteria: PickCriteria): PickHistory {
        val online = connectivity.isOnline.first()
        val rated = collection().observeSummaries().first().mapNotNull { summary -> summary.rating?.let { summary to it } }
        val indexes = rated.map { it.first.mediaId.type }.distinct().associateWith { genreIds.index(it, online) }
        val ratingsByGenre = rated
            .flatMap { (summary, rating) ->
                indexes.getValue(summary.mediaId.type).idsOf(summary.genres).map { id -> id to rating.toDouble() }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ratings) -> ratings.average() }
        return PickHistory(
            genreRatings = ratingsByGenre,
            todayEpochDay = clock.nowEpochMs().floorDiv(MS_PER_DAY),
        )
    }

    private companion object {
        const val MS_PER_DAY = 86_400_000L
    }
}
