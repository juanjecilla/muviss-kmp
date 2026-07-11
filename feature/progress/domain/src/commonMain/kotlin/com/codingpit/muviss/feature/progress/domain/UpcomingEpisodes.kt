package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season

/**
 * One library TV show's season/episode structure, as fed into
 * [UpcomingEpisodesCalculator]. Mirrors watch-next's use of raw [Season]
 * lists rather than a richer show model — the calculator only needs a title
 * to sort by and a poster to render.
 */
data class UpcomingShow(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val seasons: List<Season>,
)

/** One future episode paired with the show it belongs to — a single Upcoming agenda row. */
data class UpcomingEpisode(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val episode: Episode,
    /** Same as `episode.airDateEpochDay`, hoisted non-null since [UpcomingEpisodesCalculator] already filtered on it. */
    val airDateEpochDay: Long,
)

/** Agenda buckets the Upcoming screen groups rows into, in display order. */
enum class UpcomingBucket {
    TODAY,
    THIS_WEEK,
    LATER,
}

/** One bucket's rows, already sorted by [UpcomingEpisodesCalculator]. */
data class UpcomingGroup(
    val bucket: UpcomingBucket,
    val episodes: List<UpcomingEpisode>,
)

/**
 * Pure grouping/sorting rules for the Upcoming agenda (EPIC 14): every
 * future-dated episode (`airDateEpochDay >= today`) across the library's TV
 * shows, bucketed into Today / This week / Later and sorted by air date then
 * show title within each bucket. Episodes with a null air date are excluded
 * (nothing to schedule); a show with no future episodes (e.g. it has ended)
 * is naturally absent — there is nothing here that inspects
 * [com.codingpit.muviss.models.ProductionStatus] directly.
 */
object UpcomingEpisodesCalculator {

    // "This week" is a rolling 7-day window (today + the next 6 days), not a
    // calendar week — simplest rule that needs no day-of-week arithmetic.
    private const val THIS_WEEK_SPAN_DAYS = 6L

    fun group(shows: List<UpcomingShow>, todayEpochDay: Long): List<UpcomingGroup> {
        val rows = shows
            .flatMap { show -> show.seasons.flatMap { it.episodes }.mapNotNull { episode -> show.toUpcomingEpisodeOrNull(episode, todayEpochDay) } }
            .sortedWith(compareBy({ it.airDateEpochDay }, { it.title }))

        return UpcomingBucket.entries
            .map { bucket -> UpcomingGroup(bucket, rows.filter { bucketOf(it.airDateEpochDay, todayEpochDay) == bucket }) }
            .filter { it.episodes.isNotEmpty() }
    }

    private fun UpcomingShow.toUpcomingEpisodeOrNull(episode: Episode, todayEpochDay: Long): UpcomingEpisode? {
        val airDate = episode.airDateEpochDay
        if (airDate == null || airDate < todayEpochDay) return null
        return UpcomingEpisode(mediaId, title, posterUrl, episode, airDate)
    }

    private fun bucketOf(airDateEpochDay: Long, todayEpochDay: Long): UpcomingBucket = when {
        airDateEpochDay == todayEpochDay -> UpcomingBucket.TODAY
        airDateEpochDay <= todayEpochDay + THIS_WEEK_SPAN_DAYS -> UpcomingBucket.THIS_WEEK
        else -> UpcomingBucket.LATER
    }
}
