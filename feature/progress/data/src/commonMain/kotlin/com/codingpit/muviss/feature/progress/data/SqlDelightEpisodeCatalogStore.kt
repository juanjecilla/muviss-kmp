package com.codingpit.muviss.feature.progress.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.EpisodeQueries
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogStore
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.Episode as EpisodeRow

/**
 * SQLDelight-backed [EpisodeCatalogStore] over `Episode.sq` (ADR 0013).
 *
 * Unlike [SqlDelightProgressRepository] this exposes no `Flow`: the catalog
 * changes only when something refetches it, and the refetcher already pushes
 * the new value into [EpisodeCatalogCache][com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache].
 * Observing the table as well would emit the same list a second time and
 * recompute every watch-next row for nothing.
 *
 * [load] costs one query per id rather than one `IN (...)` query: SQLDelight
 * binds a fixed parameter list, so a variadic `IN` means either string
 * concatenation or a query per arity. The call sites pass the titles a person
 * is actively watching, which is a small number.
 */
class SqlDelightEpisodeCatalogStore(
    private val queries: EpisodeQueries,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : EpisodeCatalogStore {

    override suspend fun load(mediaIds: List<MediaId>): Map<MediaId, List<Season>> = withContext(dispatchers.io) {
        mediaIds.associateWith { queries.selectForMedia(it.toString()).awaitAsList() }
            .filterValues { it.isNotEmpty() }
            .mapValues { (_, rows) -> rows.toSeasons() }
    }

    override suspend fun save(mediaId: MediaId, seasons: List<Season>) = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        queries.transaction {
            queries.deleteForMedia(mediaId.toString())
            seasons.forEach { season ->
                season.episodes.forEach { episode -> queries.upsert(season, episode, now) }
            }
        }
    }

    /**
     * Rows arrive ordered by season then episode (see `selectForMedia`), so
     * grouping preserves show order without sorting anything here.
     */
    private fun List<EpisodeRow>.toSeasons(): List<Season> = groupBy { it.seasonNumber }
        .map { (number, rows) ->
            Season(
                number = number.toInt(),
                name = rows.first().seasonName,
                episodes = rows.map(::toEpisode),
            )
        }

    private fun toEpisode(row: EpisodeRow): Episode = Episode(
        id = EpisodeId.parse(row.episodeId),
        seasonNumber = row.seasonNumber.toInt(),
        episodeNumber = row.episodeNumber.toInt(),
        name = row.name,
        airDateEpochDay = row.airDateEpochDay,
        stillUrl = row.stillUrl,
        runtimeMinutes = row.runtimeMinutes?.toInt(),
    )

    private suspend fun EpisodeQueries.upsert(season: Season, episode: Episode, now: Long) = upsert(
        episodeId = episode.id.toString(),
        mediaId = episode.id.show.toString(),
        seasonNumber = episode.seasonNumber.toLong(),
        seasonName = season.name,
        episodeNumber = episode.episodeNumber.toLong(),
        name = episode.name,
        airDateEpochDay = episode.airDateEpochDay,
        stillUrl = episode.stillUrl,
        runtimeMinutes = episode.runtimeMinutes?.toLong(),
        fetchedAtEpochMs = now,
    )
}
