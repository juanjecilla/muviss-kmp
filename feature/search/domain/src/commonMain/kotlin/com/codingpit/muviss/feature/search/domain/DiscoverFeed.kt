package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MutedGenres
import com.codingpit.muviss.models.PagedResult

/** A value the cache holds, with the moment it was fetched from TMDB (#724). */
data class Saved<T>(val value: T, val fetchedAtEpochMs: Long)

/**
 * The stored raw TMDB responses behind Discover (#724). Raw means before Muted Genres: the caller filters at
 * read, so a mute change never shows stale titles. Keys carry the language, so a language change is a miss.
 * [NoDiscoverCache] is the cache of a test or a build without one.
 */
interface DiscoverCache {
    suspend fun genres(type: MediaType): Saved<List<Genre>>?
    suspend fun saveGenres(type: MediaType, genres: List<Genre>, fetchedAtEpochMs: Long)
    suspend fun popular(type: MediaType, page: Int): Saved<PagedResult<MediaSummary>>?
    suspend fun savePopular(type: MediaType, page: Int, result: PagedResult<MediaSummary>, fetchedAtEpochMs: Long)
}

object NoDiscoverCache : DiscoverCache {
    override suspend fun genres(type: MediaType): Saved<List<Genre>>? = null
    override suspend fun saveGenres(type: MediaType, genres: List<Genre>, fetchedAtEpochMs: Long) = Unit
    override suspend fun popular(type: MediaType, page: Int): Saved<PagedResult<MediaSummary>>? = null
    override suspend fun savePopular(type: MediaType, page: Int, result: PagedResult<MediaSummary>, fetchedAtEpochMs: Long) = Unit
}

/**
 * One popular shelf as stored: its raw titles across the pages read, and when the oldest page was fetched.
 * [complete] is false when a supplemental page the shelf wanted failed, so the titles are a partial read and a
 * stored copy that holds more of the shelf is kept instead (#724).
 */
data class DiscoverShelf(val items: List<MediaSummary>, val fetchedAtEpochMs: Long, val complete: Boolean = true)

/** What Discover can show before the network answers. A null part was never fetched (or was evicted by a newer build). */
data class DiscoverSnapshot(
    val movieGenres: Saved<List<Genre>>?,
    val tvGenres: Saved<List<Genre>>?,
    val popularMovies: DiscoverShelf?,
    val popularTv: DiscoverShelf?,
) {
    val isEmpty: Boolean get() = movieGenres == null && tvGenres == null && popularMovies == null && popularTv == null

    /** The oldest fetch among what is held, or null when nothing is. Drives the TTL and the saved-copy notice. */
    val oldestFetchedAtEpochMs: Long?
        get() = listOfNotNull(movieGenres?.fetchedAtEpochMs, tvGenres?.fetchedAtEpochMs, popularMovies?.fetchedAtEpochMs, popularTv?.fetchedAtEpochMs)
            .minOrNull()
}

/** The outcome of one network refresh, per section, so a failed section keeps its stored copy (#724). */
data class DiscoverRefresh(
    val movieGenres: Result<List<Genre>>,
    val tvGenres: Result<List<Genre>>,
    val popularMovies: Result<DiscoverShelf>,
    val popularTv: Result<DiscoverShelf>,
    val fetchedAtEpochMs: Long,
) {
    private val sections: List<Result<*>> get() = listOf(movieGenres, tvGenres, popularMovies, popularTv)

    /** A section failed, or a shelf came back partial: either way the copy shown is not a full refresh. */
    val anyFailed: Boolean get() = sections.any { it.isFailure } || listOf(popularMovies, popularTv).any { it.getOrNull()?.complete == false }

    /** The first failure, to report when there is nothing stored to show instead. */
    val firstFailure: Throwable? get() = sections.firstNotNullOfOrNull { it.exceptionOrNull() }

    /**
     * This refresh's answers where they succeeded, and [snapshot]'s stored copy where they did not. A partial
     * shelf keeps the stored one when there is one, since that copy holds more of the shelf; with nothing stored
     * the partial read is all there is.
     */
    fun mergedInto(snapshot: DiscoverSnapshot): DiscoverSnapshot = DiscoverSnapshot(
        movieGenres = movieGenres.getOrNull()?.let { Saved(it, fetchedAtEpochMs) } ?: snapshot.movieGenres,
        tvGenres = tvGenres.getOrNull()?.let { Saved(it, fetchedAtEpochMs) } ?: snapshot.tvGenres,
        popularMovies = popularMovies.shelfOr(snapshot.popularMovies),
        popularTv = popularTv.shelfOr(snapshot.popularTv),
    )

    private fun Result<DiscoverShelf>.shelfOr(stored: DiscoverShelf?): DiscoverShelf? {
        val fresh = getOrNull() ?: return stored
        return if (fresh.complete) fresh else stored ?: fresh
    }
}

/**
 * Discover's offline cache policy (#724): read what was stored first, refresh from TMDB only when a part is
 * older than [TTL_MS] or was never fetched, and store every successful answer. A popular shelf reads page 1
 * and, when the muted genres leave fewer than [SHELF_MIN_TITLES] titles, pages 2 and 3, so the shelf is not
 * thin. The live request still sends the muted genres to TMDB (`without_genres`), as it always did; what is
 * stored is that answer, and [visible] filters again at read, so a newly muted genre never shows offline.
 */
class DiscoverFeed(
    private val repository: SearchRepository,
    private val cache: DiscoverCache,
    private val clock: AppClock,
) {
    /** What is stored, read with the muted genres now in force, which decide whether a stored shelf was complete. */
    suspend fun snapshot(mutedMovies: MutedGenres = MutedGenres.NONE, mutedTv: MutedGenres = MutedGenres.NONE): DiscoverSnapshot = DiscoverSnapshot(
        movieGenres = cache.genres(MediaType.MOVIE),
        tvGenres = cache.genres(MediaType.TV),
        popularMovies = popularShelf(MediaType.MOVIE, mutedMovies),
        popularTv = popularShelf(MediaType.TV, mutedTv),
    )

    /**
     * True when [snapshot] needs the network: a part is missing, a shelf came back partial (so a refresh is owed
     * even when it is fresh), or the oldest part is past the TTL.
     */
    fun needsRefresh(snapshot: DiscoverSnapshot): Boolean {
        val complete = snapshot.movieGenres != null && snapshot.tvGenres != null && snapshot.popularMovies != null && snapshot.popularTv != null
        val partial = listOf(snapshot.popularMovies, snapshot.popularTv).any { it?.complete == false }
        return !complete || partial || isPastTtl(snapshot)
    }

    /** Fetches every section, storing each success. [mutedMovies] and [mutedTv] decide how far a shelf reads. */
    suspend fun refresh(mutedMovies: MutedGenres, mutedTv: MutedGenres): DiscoverRefresh = DiscoverRefresh(
        movieGenres = genres(MediaType.MOVIE),
        tvGenres = genres(MediaType.TV),
        popularMovies = shelf(MediaType.MOVIE, mutedMovies),
        popularTv = shelf(MediaType.TV, mutedTv),
        fetchedAtEpochMs = clock.nowEpochMs(),
    )

    /** True when the oldest stored part is past [TTL_MS]: what the person sees is then a saved copy, not a recent one. */
    fun isPastTtl(snapshot: DiscoverSnapshot): Boolean {
        val oldest = snapshot.oldestFetchedAtEpochMs ?: return false
        return clock.nowEpochMs() - oldest > TTL_MS
    }

    private suspend fun genres(type: MediaType): Result<List<Genre>> = repository.genres(type).onSuccess { list ->
        cache.saveGenres(type, list, clock.nowEpochMs())
    }

    private suspend fun shelf(type: MediaType, muted: MutedGenres): Result<DiscoverShelf> {
        val now = clock.nowEpochMs()
        val excluded = muted.forType(type)
        val first = repository.discover(type, page = 1, genreId = null, excludedGenreIds = excluded)
            .onSuccess { cache.savePopular(type, 1, it, now) }
            .getOrElse { return Result.failure(it) }
        val pages = mutableListOf(first)
        var complete = true
        while (needsMorePages(pages, muted)) {
            val page = pages.last().page + 1
            val next = repository.discover(type, page = page, genreId = null, excludedGenreIds = excluded)
                .onSuccess { cache.savePopular(type, page, it, now) }
                .getOrNull()
            if (next == null) {
                complete = false
                break
            }
            pages += next
        }
        return Result.success(DiscoverShelf(items = pages.flatMap { it.items }, fetchedAtEpochMs = now, complete = complete))
    }

    /**
     * True while the shelf has fewer titles than it should show, a page after the last one read exists, and the
     * page budget ([MAX_SHELF_PAGES]) is not spent. The budget bounds the requests as well as the stored copy.
     */
    private fun needsMorePages(pages: List<PagedResult<MediaSummary>>, muted: MutedGenres): Boolean {
        if (pages.size >= MAX_SHELF_PAGES) return false
        if (visible(pages.flatMap { it.items }, muted).size >= SHELF_MIN_TITLES) return false
        return pages.last().page < pages.last().totalPages
    }

    /**
     * The stored pages of a shelf, read in order until one is missing or is the last. The shelf is complete unless
     * it stopped short of what a refresh owed it: fewer pages than the budget, a next page that was never stored,
     * and too few visible titles to stop there. That is the state a failed supplemental page leaves behind.
     */
    private suspend fun popularShelf(type: MediaType, muted: MutedGenres): DiscoverShelf? {
        val stored = mutableListOf<Saved<PagedResult<MediaSummary>>>()
        var page = 1
        var more = true
        while (more && page <= MAX_SHELF_PAGES) {
            val next = cache.popular(type, page)
            if (next != null) stored += next
            more = next != null && next.value.page < next.value.totalPages
            page++
        }
        if (stored.isEmpty()) return null
        val items = stored.flatMap { it.value.items }
        val last = stored.last().value
        val owedMore = stored.size < MAX_SHELF_PAGES && last.page < last.totalPages && visible(items, muted).size < SHELF_MIN_TITLES
        return DiscoverShelf(items = items, fetchedAtEpochMs = stored.minOf { it.fetchedAtEpochMs }, complete = !owedMore)
    }

    /**
     * What the person would see: the stored titles minus the muted genres, each title once. Muting is applied here,
     * never stored. A title can repeat across the pages of a shelf, and a lazy row cannot show one id twice.
     */
    fun visible(items: List<MediaSummary>, muted: MutedGenres): List<MediaSummary> = items.distinctBy { it.id }.filterNot(muted::hides)

    companion object {
        /** A day is too long to trust a popularity order (TMDB builds it from daily counts). */
        const val TTL_MS: Long = 6L * 60 * 60 * 1000

        /** The fewest titles a shelf should show after muting; below it the shelf reads more pages. */
        const val SHELF_MIN_TITLES = 12

        /** The pages a shelf may read, so the stored copy stays small. */
        const val MAX_SHELF_PAGES = 3
    }
}
