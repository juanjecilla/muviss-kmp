package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.erase.DataEraseFence
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MutedGenres
import com.codingpit.muviss.models.PagedResult

/** A value the cache holds, with the moment it was fetched from TMDB (#724). */
data class Saved<T>(val value: T, val fetchedAtEpochMs: Long)

/** One seed's raw recommendations as stored for "For you" (#785), and when TMDB answered for it. */
data class ForYouAnswer(val seed: MediaId, val items: List<MediaSummary>, val fetchedAtEpochMs: Long)

/**
 * What a Discover or For you run captured before it first suspended (#965). Every read and write the run makes is
 * keyed by [language], fenced by [erasure] and stamped with [startedAtEpochMs]. None of them reads the ambient
 * language, erase count or clock again, so a change during the run can never reach what it stores.
 */
data class DiscoverRun(val language: String, val erasure: Long, val startedAtEpochMs: Long)

/**
 * One "For you" run: the [run] it captured, the seeds the library gave it, and the seeds it must ask TMDB for even
 * when their stored answer is fresh: those whose request failed and are still owed, or every seed on a Retry (#924).
 * The stored copy is read with this run rather than held in it, because it cannot be known before that read.
 */
data class ForYouRun(val run: DiscoverRun, val seeds: List<MediaId>, val mustAsk: Set<MediaId> = emptySet())

/**
 * A For you refresh: the [answers] in the seeds' order, and the [failed] seeds whose request failed. A failed seed
 * is owed again on the next run whatever its stored answer's age, so a fresh copy never hides a missed request.
 */
data class ForYouRefresh(val answers: List<ForYouAnswer>, val failed: Set<MediaId>, val asked: Set<MediaId> = emptySet())

/**
 * The stored raw TMDB responses behind Discover (#724), and "For you"'s copy (#785). Raw means before Muted Genres
 * and before the library: the caller filters at read, so a mute change never shows stale titles. Every method takes
 * the [language] its request was made in, so a language change is a miss on read, and a write is kept under the
 * language that produced it. [NoDiscoverCache] is the cache of a test or a build without one.
 */
interface DiscoverCache {
    /** The TMDB language this device reads and writes Discover in now. A run takes it once, when it starts. */
    fun language(): String = ""

    suspend fun genres(type: MediaType, language: String): Saved<List<Genre>>?
    suspend fun saveGenres(type: MediaType, genres: List<Genre>, fetchedAtEpochMs: Long, language: String)
    suspend fun popular(type: MediaType, page: Int, language: String): Saved<PagedResult<MediaSummary>>?
    suspend fun savePopular(type: MediaType, page: Int, result: PagedResult<MediaSummary>, fetchedAtEpochMs: Long, language: String)

    /** The answers stored for the seed set [seeds] in [language], or null when that set has none. A different set is a miss. */
    suspend fun forYou(seeds: List<MediaId>, language: String): List<ForYouAnswer>? = null

    /** Stores [answers] for the seed set [seeds], written in [language]. An answer replaces a stored one only when it is newer. */
    suspend fun saveForYou(seeds: List<MediaId>, answers: List<ForYouAnswer>, language: String) = Unit
}

object NoDiscoverCache : DiscoverCache {
    override suspend fun genres(type: MediaType, language: String): Saved<List<Genre>>? = null
    override suspend fun saveGenres(type: MediaType, genres: List<Genre>, fetchedAtEpochMs: Long, language: String) = Unit
    override suspend fun popular(type: MediaType, page: Int, language: String): Saved<PagedResult<MediaSummary>>? = null
    override suspend fun savePopular(type: MediaType, page: Int, result: PagedResult<MediaSummary>, fetchedAtEpochMs: Long, language: String) = Unit
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
    // Delete all data's fence (#965): a write for a run begun before an erase is dropped, not stored.
    private val erasures: DataEraseFence = DataEraseFence(),
) {
    /** Captures what a Discover run needs before it suspends. Every Discover run starts here. */
    fun startRun(): DiscoverRun = DiscoverRun(language = cache.language(), erasure = erasures.request(), startedAtEpochMs = clock.nowEpochMs())

    /** Captures what a For you run needs, with the seeds the library gave it and the ones it must ask for regardless (#924). */
    fun startForYou(seeds: List<MediaId>, mustAsk: Set<MediaId> = emptySet()): ForYouRun = ForYouRun(startRun(), seeds, mustAsk)

    /** What is stored in [run]'s language, read with the muted genres now in force, which decide whether a stored shelf was complete. */
    suspend fun snapshot(run: DiscoverRun, mutedMovies: MutedGenres = MutedGenres.NONE, mutedTv: MutedGenres = MutedGenres.NONE): DiscoverSnapshot = DiscoverSnapshot(
        movieGenres = cache.genres(MediaType.MOVIE, run.language),
        tvGenres = cache.genres(MediaType.TV, run.language),
        popularMovies = popularShelf(MediaType.MOVIE, mutedMovies, run.language),
        popularTv = popularShelf(MediaType.TV, mutedTv, run.language),
    )

    /**
     * True when [snapshot] needs the network: a part is missing, a shelf came back partial (so a refresh is owed
     * even when it is fresh), or the oldest part was past the TTL when [run] began.
     */
    fun needsRefresh(snapshot: DiscoverSnapshot, run: DiscoverRun): Boolean {
        val complete = snapshot.movieGenres != null && snapshot.tvGenres != null && snapshot.popularMovies != null && snapshot.popularTv != null
        val partial = listOf(snapshot.popularMovies, snapshot.popularTv).any { it?.complete == false }
        return !complete || partial || isPastTtl(snapshot, run)
    }

    /**
     * The stored "For you" answers for [run]'s seeds, in seed order, or null when no copy is stored for this seed set
     * in this language. A seed set that changed is a miss, so a library change never shows the previous set's titles.
     */
    suspend fun forYouStored(run: ForYouRun): List<ForYouAnswer>? {
        val stored = cache.forYou(run.seeds, run.run.language) ?: return null
        return run.seeds.mapNotNull { seed -> stored.firstOrNull { it.seed == seed } }
    }

    /**
     * [run]'s seeds' answers after a refresh. A seed is asked of TMDB when its stored answer is missing, past [TTL_MS]
     * (at the moment the run began), or in [ForYouRun.mustAsk]; so a library change that keeps the seed set costs no
     * request. A seed that fails keeps its stored answer with its old fetch time and is reported in [ForYouRefresh.failed];
     * with nothing stored it contributes nothing. What succeeded is stored in [run]'s language, unless Delete all data
     * ran since the run began ([DataEraseFence]). Answers are returned in the seeds' order.
     */
    suspend fun refreshForYou(
        run: ForYouRun,
        stored: List<ForYouAnswer>?,
        recommendations: suspend (MediaId) -> Result<PagedResult<MediaSummary>>,
    ): ForYouRefresh {
        val now = run.run.startedAtEpochMs
        val seeds = run.seeds
        val byId = stored.orEmpty().associateBy { it.seed }
        val due = seeds.filter { seed -> seed in run.mustAsk || (byId[seed]?.let { now - it.fetchedAtEpochMs > TTL_MS } ?: true) }
        val asked = due.associateWith { seed -> recommendations(seed).map { it.items } }
        val answers = seeds.mapNotNull { seed ->
            asked[seed]?.getOrNull()?.let { ForYouAnswer(seed, it, now) } ?: byId[seed]
        }
        if (asked.values.any { it.isSuccess }) {
            erasures.write(run.run.erasure) { cache.saveForYou(seeds, answers, run.run.language) }
        }
        return ForYouRefresh(answers, failed = asked.filterValues { it.isFailure }.keys, asked = asked.keys)
    }

    /**
     * Fetches every section, storing each success. [mutedMovies] and [mutedTv] decide how far a shelf reads. Every
     * request is [fresh][SearchRepository.discover]: a refresh is owed a current answer, so the in-memory HTTP cache
     * may not answer it (#922).
     */
    suspend fun refresh(run: DiscoverRun, mutedMovies: MutedGenres, mutedTv: MutedGenres): DiscoverRefresh = DiscoverRefresh(
        movieGenres = genres(MediaType.MOVIE, run),
        tvGenres = genres(MediaType.TV, run),
        popularMovies = shelf(MediaType.MOVIE, mutedMovies, run),
        popularTv = shelf(MediaType.TV, mutedTv, run),
        fetchedAtEpochMs = run.startedAtEpochMs,
    )

    /** True when the oldest stored part was past [TTL_MS] when [run] began: what the person sees is then a saved copy, not a recent one. */
    fun isPastTtl(snapshot: DiscoverSnapshot, run: DiscoverRun): Boolean {
        val oldest = snapshot.oldestFetchedAtEpochMs ?: return false
        return run.startedAtEpochMs - oldest > TTL_MS
    }

    private suspend fun genres(type: MediaType, run: DiscoverRun): Result<List<Genre>> = repository.genres(type, fresh = true).onSuccess { list ->
        erasures.write(run.erasure) { cache.saveGenres(type, list, run.startedAtEpochMs, run.language) }
    }

    private suspend fun shelf(type: MediaType, muted: MutedGenres, run: DiscoverRun): Result<DiscoverShelf> {
        val now = run.startedAtEpochMs
        val excluded = muted.forType(type)
        val first = repository.discover(type, page = 1, genreId = null, excludedGenreIds = excluded, fresh = true)
            .onSuccess { erasures.write(run.erasure) { cache.savePopular(type, 1, it, now, run.language) } }
            .getOrElse { return Result.failure(it) }
        val pages = mutableListOf(first)
        var complete = true
        while (needsMorePages(pages, muted)) {
            val page = pages.last().page + 1
            val next = repository.discover(type, page = page, genreId = null, excludedGenreIds = excluded, fresh = true)
                .onSuccess { erasures.write(run.erasure) { cache.savePopular(type, page, it, now, run.language) } }
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
    private suspend fun popularShelf(type: MediaType, muted: MutedGenres, language: String): DiscoverShelf? {
        val stored = mutableListOf<Saved<PagedResult<MediaSummary>>>()
        var page = 1
        var more = true
        while (more && page <= MAX_SHELF_PAGES) {
            val next = cache.popular(type, page, language)
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
