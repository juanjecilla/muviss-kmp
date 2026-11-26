package com.codingpit.muviss.appfunctions

import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.WatchStatus

/** "S1E2 Pilot": the season, episode and name an agent reads back to the user. */
internal fun episodeLabel(episode: Episode): String = "S${episode.seasonNumber}E${episode.episodeNumber} ${episode.name}".trimEnd()

internal fun WatchNextItem.toEntry(): WatchNextEntry = WatchNextEntry(
    mediaId = mediaId.toString(),
    title = title,
    nextEpisodeId = nextEpisode?.id?.toString(),
    nextEpisodeLabel = nextEpisode?.let(::episodeLabel),
    catalogLoaded = catalogLoaded,
    seenCount = seenCount,
    airedCount = airedCount,
)

internal fun MediaSummary.toHit(): TitleHit = TitleHit(
    mediaId = id.toString(),
    title = title,
    type = id.type.agentName(),
    year = year,
)

internal fun CollectionSummary.toLibraryEntry(): LibraryEntry = LibraryEntry(
    mediaId = mediaId.toString(),
    title = title,
    status = status.name,
    favorite = favorite,
)

internal fun MediaType.agentName(): String = when (this) {
    MediaType.MOVIE -> "movie"
    MediaType.TV -> "tv"
}

/** The status an agent may filter on, as the wire name. */
internal fun WatchStatus.agentName(): String = name
