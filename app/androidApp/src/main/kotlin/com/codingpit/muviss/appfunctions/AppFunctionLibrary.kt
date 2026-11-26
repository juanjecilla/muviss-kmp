package com.codingpit.muviss.appfunctions

import androidx.appfunctions.AppFunctionElementNotFoundException
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.progress.api.EpisodeOrdering
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.SourceId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * What the AppFunctions do, written against the feature `:api` contracts only (ADR 0004). It holds no Android
 * types, so the service class is a thin declaration layer over it. Every call runs on [Dispatchers.IO]: AppFunction
 * bodies start on the main thread.
 *
 * Writes on a title require the title to be in the library first (ADR 0037: a tick on a title that is not saved
 * leaves progress the user cannot see). Only `seen = true` is ever written: `setEpisodeSeen(…, false)` clears
 * an episode's history, so there is no un-tick here.
 */
internal class AppFunctionLibrary(
    private val progress: ProgressApi,
    private val collection: CollectionApi,
    private val providers: MetadataProviderRegistry,
    private val clock: AppClock,
) {

    suspend fun watchNext(): List<WatchNextEntry> = withContext(Dispatchers.IO) {
        progress.observeWatchNext().first().map { it.toEntry() }
    }

    suspend fun searchTitles(query: String, type: MediaType?): List<TitleHit> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isEmpty()) throw invalidArgument("query must not be blank.")
        providers.require(SourceId.TMDB).search(text).items
            .filter { type == null || it.id.type == type }
            .take(SEARCH_LIMIT)
            .map { it.toHit() }
    }

    suspend fun library(status: WatchStatus?, favoritesOnly: Boolean): List<LibraryEntry> = withContext(Dispatchers.IO) {
        collection.observeSummaries().first()
            .filter { status == null || it.status == status }
            .filter { !favoritesOnly || it.favorite }
            .map { it.toLibraryEntry() }
    }

    suspend fun titleProgress(mediaId: MediaId): TitleProgress = withContext(Dispatchers.IO) {
        val summary = savedSummary(mediaId)
        val seen = progress.observeSeenEpisodes(mediaId).first()
        if (mediaId.type == MediaType.MOVIE) {
            val watched = EpisodeId.forMovie(mediaId) in seen
            return@withContext TitleProgress(
                mediaId = mediaId.toString(),
                title = summary.title,
                status = summary.status.agentName(),
                seenCount = if (watched) 1 else 0,
                airedCount = 1,
                nextEpisodeId = null,
                nextEpisodeLabel = null,
            )
        }
        val today = clock.todayEpochDay()
        val seasons = progress.storedSeasons(mediaId).orEmpty()
        val next = progress.nextEpisodes(seasons, seen, today).aired
        TitleProgress(
            mediaId = mediaId.toString(),
            title = summary.title,
            status = summary.status.agentName(),
            seenCount = seen.size,
            airedCount = EpisodeOrdering.airedBy(seasons, today).size,
            nextEpisodeId = next?.id?.toString(),
            nextEpisodeLabel = next?.let(::episodeLabel),
        )
    }

    suspend fun tickEpisode(episodeId: EpisodeId) = withContext(Dispatchers.IO) {
        if (episodeId.show.type != MediaType.TV) {
            throw invalidArgument("tickEpisode needs an episodeId of a TV show. Use markMovieWatched for a movie.")
        }
        savedSummary(episodeId.show)
        val seasons = progress.storedSeasons(episodeId.show)
            ?: throw AppFunctionElementNotFoundException(
                "Muviss has not loaded this show's episodes yet. Open the show in Muviss, then try again.",
            )
        val episode = EpisodeOrdering.flatten(seasons).firstOrNull { it.id == episodeId }
            ?: throw AppFunctionElementNotFoundException(
                "That episode is not in this show's episode list. Call getWatchNext for a valid episodeId.",
            )
        if (!EpisodeOrdering.hasAired(episode, clock.todayEpochDay())) {
            throw invalidArgument("That episode has not aired yet, so it cannot be ticked.")
        }
        progress.setEpisodeSeen(episodeId, seen = true)
    }

    suspend fun addToLibrary(mediaId: MediaId) = withContext(Dispatchers.IO) {
        if (providers.all.none { it.source == mediaId.source }) {
            throw invalidArgument("mediaId names a source Muviss does not use. Use an id returned by searchTitles.")
        }
        collection.add(providers.require(mediaId.source).details(mediaId))
    }

    suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = withContext(Dispatchers.IO) {
        savedSummary(mediaId)
        collection.setFavorite(mediaId, favorite)
    }

    suspend fun rateTitle(mediaId: MediaId, rating: Int) = withContext(Dispatchers.IO) {
        savedSummary(mediaId)
        collection.setRating(mediaId, rating)
    }

    suspend fun markMovieWatched(mediaId: MediaId) = withContext(Dispatchers.IO) {
        if (mediaId.type != MediaType.MOVIE) {
            throw invalidArgument("markMovieWatched needs a movie mediaId. Use tickEpisode for a TV show.")
        }
        savedSummary(mediaId)
        progress.setMovieWatched(mediaId, watched = true)
    }

    /** The library row for [mediaId], or the "add it first" failure every write on a title starts with. */
    private suspend fun savedSummary(mediaId: MediaId): CollectionSummary = collection.observeSummaries().first()
        .firstOrNull { it.mediaId == mediaId }
        ?: throw AppFunctionElementNotFoundException("That title is not in the library. Call addToLibrary with its mediaId first.")

    private companion object {
        const val SEARCH_LIMIT = 10
    }
}
