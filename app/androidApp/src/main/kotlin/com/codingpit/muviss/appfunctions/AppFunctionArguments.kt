package com.codingpit.muviss.appfunctions

import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.WatchStatus

/** The wire names an agent may send for a media type. Lower case, as the descriptions say. */
internal fun parseMediaType(raw: String?): MediaType? = when (raw?.trim()?.lowercase()) {
    null, "" -> null
    "movie" -> MediaType.MOVIE
    "tv" -> MediaType.TV
    else -> throw invalidArgument("type must be \"movie\" or \"tv\", or omitted for both.")
}

/** A mediaId such as `tmdb:tv:1399`. Every parse failure becomes an argument error; nothing from the parser is kept. */
internal fun parseMediaId(raw: String): MediaId = try {
    MediaId.parse(raw.trim())
} catch (e: IllegalArgumentException) {
    throw invalidArgument(MEDIA_ID_HELP, e)
} catch (e: IllegalStateException) {
    throw invalidArgument(MEDIA_ID_HELP, e)
}

/** An episodeId such as `tmdb:tv:1399/1/2`. */
internal fun parseEpisodeId(raw: String): EpisodeId = try {
    EpisodeId.parse(raw.trim())
} catch (e: IllegalArgumentException) {
    throw invalidArgument(EPISODE_ID_HELP, e)
} catch (e: IllegalStateException) {
    throw invalidArgument(EPISODE_ID_HELP, e)
}

/** A rating must be a whole number from 1 to 10. Clearing a rating is not offered to agents. */
internal fun parseRating(raw: Int): Int {
    if (raw !in RATING_RANGE) throw invalidArgument("rating must be a whole number from 1 to 10.")
    return raw
}

/** A library filter; null means "any status". */
internal fun parseStatus(raw: String?): WatchStatus? {
    if (raw.isNullOrBlank()) return null
    return WatchStatus.entries.firstOrNull { it.name == raw.trim().uppercase() }
        ?: throw invalidArgument("status must be one of NOT_STARTED, WATCHING, WATCHED or FINISHED.")
}

internal val RATING_RANGE = 1..10

private const val MEDIA_ID_HELP = "mediaId must look like tmdb:movie:603 or tmdb:tv:1399, as returned by searchTitles or getWatchNext."
private const val EPISODE_ID_HELP = "episodeId must look like tmdb:tv:1399/1/2 (show, season, episode), as returned by getWatchNext."
