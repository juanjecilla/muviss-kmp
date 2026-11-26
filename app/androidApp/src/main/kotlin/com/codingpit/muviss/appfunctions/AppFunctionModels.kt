package com.codingpit.muviss.appfunctions

import androidx.appfunctions.AppFunctionSerializable

/** One title the user is part-way through, and its next episode: the answer to "what do I watch next". */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class WatchNextEntry(
    /** The title's mediaId, such as tmdb:tv:1399. */
    val mediaId: String,
    /** The title's name. */
    val title: String,
    /** The episodeId to pass to tickEpisode, such as tmdb:tv:1399/1/2, or null when no aired episode is unseen. */
    val nextEpisodeId: String?,
    /** The next episode's season, number and name, such as "S1E2 Pilot", or null when there is none. */
    val nextEpisodeLabel: String?,
    /** False until Muviss has loaded this title's episodes. Then nextEpisodeId is unknown, not "caught up". */
    val catalogLoaded: Boolean,
    /** How many episodes of this title the user has seen. */
    val seenCount: Int,
    /** How many episodes of this title have aired so far. */
    val airedCount: Int,
)

/** One search result from the movie database. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class TitleHit(
    /** The mediaId to pass to addToLibrary, such as tmdb:movie:603. */
    val mediaId: String,
    /** The title's name. */
    val title: String,
    /** Either "movie" or "tv". */
    val type: String,
    /** The release year, or null when the database has none. */
    val year: Int?,
)

/** One title in the user's library. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class LibraryEntry(
    /** The title's mediaId, such as tmdb:movie:603. */
    val mediaId: String,
    /** The title's name. */
    val title: String,
    /** One of NOT_STARTED, WATCHING, WATCHED or FINISHED. NOT_STARTED is the watchlist. */
    val status: String,
    /** Whether the user marked the title as a favorite. */
    val favorite: Boolean,
)

/** How far the user has got with one title. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class TitleProgress(
    /** The title's mediaId. */
    val mediaId: String,
    /** The title's name. */
    val title: String,
    /** One of NOT_STARTED, WATCHING, WATCHED or FINISHED. */
    val status: String,
    /** False until Muviss has loaded this title's episodes; then airedCount and the next episode are unknown. */
    val catalogLoaded: Boolean,
    /**
     * How many aired episodes the user has seen, or 1 for a movie when it is watched. While catalogLoaded is false
     * nothing is known to have aired, so this counts every episode the user has ticked.
     */
    val seenCount: Int,
    /** How many episodes have aired, or 1 for a movie. Zero when Muviss has not loaded the episodes yet. */
    val airedCount: Int,
    /** The episodeId of the next aired unseen episode, or null when there is none. */
    val nextEpisodeId: String?,
    /** The next episode's season, number and name, or null when there is none. */
    val nextEpisodeLabel: String?,
)
