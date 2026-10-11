package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season

internal const val TODAY_EPOCH_DAY = 20_000L
internal const val NOW_EPOCH_MS = TODAY_EPOCH_DAY * 86_400_000L

internal fun movie(id: String = "1", title: String = "Movie $id") = MediaSummary(MediaId.tmdbMovie(id), title)

internal fun show(id: String = "10", title: String = "Show $id") = MediaSummary(MediaId.tmdbTv(id), title)

internal fun episode(show: MediaId, season: Int, number: Int, airDay: Long?) = Episode(
    id = EpisodeId(show, season, number),
    seasonNumber = season,
    episodeNumber = number,
    name = "S${season}E$number",
    airDateEpochDay = airDay,
)

/** A show whose seasons are described as `season number to list of air days` (null = no date yet). */
internal fun tvDetails(
    summary: MediaSummary = show(),
    seasons: List<Pair<Int, List<Long?>>>,
    productionStatus: ProductionStatus = ProductionStatus.RETURNING,
): MediaDetails = MediaDetails(
    summary = summary,
    productionStatus = productionStatus,
    seasons = seasons.map { (number, airDays) ->
        Season(
            number = number,
            name = "Season $number",
            episodes = airDays.mapIndexed { index, day -> episode(summary.id, number, index + 1, day) },
        )
    },
)

internal fun movieDetails(summary: MediaSummary = movie()) = MediaDetails(
    summary = summary,
    productionStatus = ProductionStatus.RELEASED,
)

/** Serves fixed pages per media type, counting how often it is asked. */
internal class FakeDeckSource(
    private val moviePages: List<List<MediaSummary>> = emptyList(),
    private val tvPages: List<List<MediaSummary>> = emptyList(),
    private val failure: Throwable? = null,
) : DeckSource {
    var pageCalls = 0
        private set

    override suspend fun page(
        type: com.codingpit.muviss.models.MediaType,
        page: Int,
        genreId: String?,
    ): Result<PagedResult<MediaSummary>> {
        pageCalls++
        failure?.let { return Result.failure(it) }
        val pages = if (type == com.codingpit.muviss.models.MediaType.MOVIE) moviePages else tvPages
        val items = pages.getOrNull(page - 1).orEmpty()
        return Result.success(PagedResult(items, page = page, totalPages = maxOf(pages.size, 1)))
    }

    override suspend fun genres(type: com.codingpit.muviss.models.MediaType) = Result.success(emptyList<com.codingpit.muviss.models.Genre>())
}
