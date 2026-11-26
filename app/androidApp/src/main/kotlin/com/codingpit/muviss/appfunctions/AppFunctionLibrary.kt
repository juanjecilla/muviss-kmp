package com.codingpit.muviss.appfunctions

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

    /**
     * Searches TMDB and narrows to [type] after each page: the search is multi, so one page can hold people and the
     * other media type. Pages are read until [SEARCH_LIMIT] hits are found or [MAX_SEARCH_PAGES] is reached.
     */
    suspend fun searchTitles(query: String, type: MediaType?): List<TitleHit> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isEmpty()) throw invalidArgument("query must not be blank.")
        val source = providers.require(SourceId.TMDB)
        val hits = mutableListOf<TitleHit>()
        var page = 1
        var totalPages = 1
        while (hits.size < SEARCH_LIMIT && page <= totalPages && page <= MAX_SEARCH_PAGES) {
            val result = source.search(text, page)
            totalPages = result.totalPages
            hits += result.items.filter { type == null || it.id.type == type }.map { it.toHit() }
            page++
        }
        hits.take(SEARCH_LIMIT)
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
                catalogLoaded = true,
                seenCount = if (watched) 1 else 0,
                airedCount = 1,
                nextEpisodeId = null,
                nextEpisodeLabel = null,
            )
        }
        val today = clock.todayEpochDay()
        val seasons = progress.storedSeasons(mediaId)
        val aired = seasons?.let { EpisodeOrdering.airedBy(it, today).toSet() }.orEmpty()
        val next = seasons?.let { progress.nextEpisodes(it, seen, today).aired }
        TitleProgress(
            mediaId = mediaId.toString(),
            title = summary.title,
            status = summary.status.agentName(),
            catalogLoaded = seasons != null,
            // Counted over aired episodes only, the basis watch-next uses, so the two functions agree.
            seenCount = seen.count { it in aired },
            airedCount = aired.size,
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
            ?: throw MissingAgentItem("Muviss has not loaded this show's episodes yet. Open the show in Muviss, then try again.")
        val episode = EpisodeOrdering.flatten(seasons).firstOrNull { it.id == episodeId }
        when (tickRefusal(episode, clock.todayEpochDay())) {
            null -> progress.setEpisodeSeen(episodeId, seen = true)
            TickRefusal.NOT_IN_CATALOG -> throw MissingAgentItem(TickRefusal.NOT_IN_CATALOG.text)
            TickRefusal.NOT_AIRED -> throw invalidArgument(TickRefusal.NOT_AIRED.text)
        }
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
        ?: throw MissingAgentItem("That title is not in the library. Call addToLibrary with its mediaId first.")

    private companion object {
        const val SEARCH_LIMIT = 10
        const val MAX_SEARCH_PAGES = 3
    }
}
