package com.codingpit.muviss.appfunctions

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.progress.api.ProgressApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The entry point the system binds to. KSP generates `MuvissAppFunctionService` from this class; the manifest
 * names the generated one. The functions are the whole assistant surface (EPIC 701): reads and safe, silent
 * writes, nothing destructive. Each one delegates to [AppFunctionLibrary] through [guardAppFunction].
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "MuvissAppFunctionService",
    appFunctionXmlFileName = "muviss_app_function_service",
)
abstract class BaseMuvissAppFunctionService :
    AppFunctionService(),
    KoinComponent {

    private val progress: ProgressApi by inject()
    private val collection: CollectionApi by inject()
    private val providers: MetadataProviderRegistry by inject()
    private val clock: AppClock by inject()
    private val library: AppFunctionLibrary by lazy { AppFunctionLibrary(progress, collection, providers, clock) }

    /**
     * Lists the titles the user is part-way through, each with its next unseen aired episode, in the order
     * Muviss shows them on Progress. Read-only; works offline and signed out.
     *
     * @return The watch-next titles, with the episodeId to pass to tickEpisode.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getWatchNext(): List<WatchNextEntry> = guardAppFunction { library.watchNext() }

    /**
     * Searches the movie database for movies and TV shows by name. Read-only; needs a network connection.
     *
     * @param query The title or words to search for.
     * @param type "movie" or "tv" to narrow the results; null for both.
     * @return Up to ten matches, each with the mediaId to pass to addToLibrary.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun searchTitles(query: String, type: String?): List<TitleHit> = guardAppFunction { library.searchTitles(query, parseMediaType(type)) }

    /**
     * Lists the titles in the user's library, optionally filtered by status or to favorites. Read-only.
     *
     * @param status One of NOT_STARTED (the watchlist), WATCHING, WATCHED or FINISHED; null for any status.
     * @param favoritesOnly True to list only the titles the user marked as favorites.
     * @return The matching library titles.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getLibrary(status: String?, favoritesOnly: Boolean): List<LibraryEntry> = guardAppFunction { library.library(parseStatus(status), favoritesOnly) }

    /**
     * Reports how far the user has got with one title in the library. Read-only.
     * Required workflow: Call "getLibrary" or "getWatchNext" first to obtain a valid mediaId.
     *
     * @param mediaId The title's mediaId, such as tmdb:tv:1399.
     * @return The status, how many episodes are seen and aired, and the next unseen episode.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getTitleProgress(mediaId: String): TitleProgress = guardAppFunction { library.titleProgress(parseMediaId(mediaId)) }

    /**
     * Marks one aired episode of a TV show in the library as seen. Silent: it does not show a completion prompt.
     * Required workflow: Call "getWatchNext" first to obtain a valid episodeId.
     *
     * @param episodeId The episode's id, such as tmdb:tv:1399/1/2. It must be an episode that has already aired.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun tickEpisode(episodeId: String) = guardAppFunction { library.tickEpisode(parseEpisodeId(episodeId)) }

    /**
     * Saves a movie or TV show to the library, so it appears on the watchlist. Idempotent: adding a saved title
     * refreshes its details. Needs a network connection.
     * Required workflow: Call "searchTitles" first to obtain a valid mediaId.
     *
     * @param mediaId The title's mediaId, such as tmdb:movie:603.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun addToLibrary(mediaId: String) = guardAppFunction { library.addToLibrary(parseMediaId(mediaId)) }

    /**
     * Marks or unmarks a title in the library as a favorite.
     * Required workflow: Call "getLibrary" first to obtain a valid mediaId; the title must already be in the library.
     *
     * @param mediaId The title's mediaId, such as tmdb:tv:1399.
     * @param favorite True to mark the title as a favorite, false to remove the mark.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun setFavorite(mediaId: String, favorite: Boolean) = guardAppFunction { library.setFavorite(parseMediaId(mediaId), favorite) }

    /**
     * Sets the user's personal rating of a title in the library.
     * Required workflow: Call "getLibrary" first to obtain a valid mediaId; the title must already be in the library.
     *
     * @param mediaId The title's mediaId, such as tmdb:movie:603.
     * @param rating A whole number from 1 (worst) to 10 (best).
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun rateTitle(mediaId: String, rating: Int) = guardAppFunction { library.rateTitle(parseMediaId(mediaId), parseRating(rating)) }

    /**
     * Marks a movie in the library as watched. Silent: it does not show a completion prompt.
     * Required workflow: Call "getLibrary" first to obtain a valid mediaId; the movie must already be in the library.
     *
     * @param mediaId The movie's mediaId, such as tmdb:movie:603.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun markMovieWatched(mediaId: String) = guardAppFunction { library.markMovieWatched(parseMediaId(mediaId)) }
}
