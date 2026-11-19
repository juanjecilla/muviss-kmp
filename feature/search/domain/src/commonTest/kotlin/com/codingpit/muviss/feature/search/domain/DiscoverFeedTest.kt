package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.MutedGenres
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #724: Discover opens from what was stored, refreshes when it is due, and keeps a section's copy when its refresh fails. */
class DiscoverFeedTest {

    private val clock = MutableClock(1_000_000_000L)
    private val cache = MemoryDiscoverCache()
    private val repository = ScriptedRepository()
    private val feed = DiscoverFeed(repository, cache, clock)

    @Test
    fun a_cold_start_has_nothing_stored_and_needs_the_network() = runTest {
        val snapshot = feed.snapshot()

        assertTrue(snapshot.isEmpty)
        assertTrue(feed.needsRefresh(snapshot))
    }

    @Test
    fun a_refresh_stores_every_answer_so_the_next_start_opens_offline() = runTest {
        repository.movies = listOf(page(1, 1, movie("m1"), movie("m2")))
        repository.tvs = listOf(page(1, 1, show("t1")))
        repository.movieGenres = listOf(Genre("28", "Action"))
        repository.tvGenres = listOf(Genre("10759", "Action & Adventure"))

        feed.refresh(MutedGenres.NONE, MutedGenres.NONE)
        repository.failEverything = true
        val next = feed.snapshot()

        assertEquals(listOf("m1", "m2"), next.popularMovies?.items?.map { it.id.external })
        assertEquals(listOf("t1"), next.popularTv?.items?.map { it.id.external })
        assertEquals(listOf(Genre("28", "Action")), next.movieGenres?.value)
        assertFalse(feed.needsRefresh(next), "a fresh copy is shown without asking the network")
    }

    @Test
    fun a_copy_past_the_ttl_needs_a_refresh_and_is_past_the_ttl() = runTest {
        repository.movies = listOf(page(1, 1, movie("m1")))
        repository.tvs = listOf(page(1, 1, show("t1")))
        feed.refresh(MutedGenres.NONE, MutedGenres.NONE)
        val stored = feed.snapshot()

        clock.now += DiscoverFeed.TTL_MS + 1

        assertTrue(feed.needsRefresh(stored))
        assertTrue(feed.isPastTtl(stored))
    }

    @Test
    fun a_refresh_that_fails_everywhere_reports_every_section_failed() = runTest {
        repository.failEverything = true

        val refresh = feed.refresh(MutedGenres.NONE, MutedGenres.NONE)

        assertTrue(refresh.anyFailed)
        assertTrue(refresh.popularMovies.isFailure && refresh.popularTv.isFailure && refresh.movieGenres.isFailure && refresh.tvGenres.isFailure)
    }

    @Test
    fun a_failed_section_keeps_its_stored_copy_and_the_others_update() = runTest {
        repository.movies = listOf(page(1, 1, movie("m1")))
        repository.tvs = listOf(page(1, 1, show("t1")))
        repository.movieGenres = listOf(Genre("28", "Action"))
        repository.tvGenres = listOf(Genre("10759", "Action & Adventure"))
        feed.refresh(MutedGenres.NONE, MutedGenres.NONE)
        val stored = feed.snapshot()

        clock.now += 10_000
        repository.movies = listOf(page(1, 1, movie("m2")))
        repository.tvGenres = null // the TV genre list fails this time
        val refresh = feed.refresh(MutedGenres.NONE, MutedGenres.NONE)
        val merged = refresh.mergedInto(stored)

        assertEquals(listOf("m2"), merged.popularMovies?.items?.map { it.id.external }, "the movie shelf refreshed")
        assertEquals(listOf(Genre("10759", "Action & Adventure")), merged.tvGenres?.value, "the TV genres failed, so their stored copy stays")
        assertTrue(refresh.anyFailed)
    }

    @Test
    fun a_shelf_reads_more_pages_when_the_muted_genres_leave_it_thin() = runTest {
        // Page 1 is mostly muted, so the shelf reads on: page 2 is mostly muted too, and page 3 fills it up.
        repository.tvs = listOf(
            page(1, 3, *tvTitles("mute-a", 15, "10767"), *tvTitles("keep-a", 5)),
            page(2, 3, *tvTitles("mute-b", 10, "10767"), *tvTitles("keep-b", 2)),
            page(3, 3, *tvTitles("keep-c", 12)),
        )

        feed.refresh(MutedGenres.NONE, MutedGenres(setOf(GenreKey(MediaType.TV, "10767"))))

        assertEquals(listOf(1, 2, 3), repository.discoverPages(MediaType.TV), "the thin shelf read two more pages")
        assertEquals(3, cache.popular(MediaType.TV, 3)?.value?.page, "every page read is stored under its own page")
    }

    @Test
    fun a_shelf_that_is_full_enough_reads_only_its_first_page() = runTest {
        repository.tvs = listOf(page(1, 3, *tvTitles("keep", 20)))

        feed.refresh(MutedGenres.NONE, MutedGenres(setOf(GenreKey(MediaType.TV, "10767"))))

        assertEquals(listOf(1), repository.discoverPages(MediaType.TV))
    }

    @Test
    fun a_shelf_stops_at_the_last_page_when_the_catalogue_is_shorter() = runTest {
        repository.tvs = listOf(page(1, 1, *tvTitles("mute", 15, "10767")))

        feed.refresh(MutedGenres.NONE, MutedGenres(setOf(GenreKey(MediaType.TV, "10767"))))

        assertEquals(listOf(1), repository.discoverPages(MediaType.TV))
    }

    @Test
    fun muting_is_applied_at_read_so_a_newly_muted_genre_never_shows_offline() = runTest {
        repository.tvs = listOf(page(1, 1, show("talk", "10767"), show("drama", "18")))
        feed.refresh(MutedGenres.NONE, MutedGenres.NONE)

        val shown = feed.visible(feed.snapshot().popularTv!!.items, MutedGenres(setOf(GenreKey(MediaType.TV, "10767"))))

        assertEquals(listOf("drama"), shown.map { it.id.external })
    }

    @Test
    fun a_stored_page_is_looked_up_by_its_section_and_page() = runTest {
        cache.savePopular(MediaType.MOVIE, 2, page(2, 3, movie("only-page-two")), fetchedAtEpochMs = 1L)

        assertNull(cache.popular(MediaType.MOVIE, 1))
        assertEquals(2, cache.popular(MediaType.MOVIE, 2)?.value?.page)
    }

    @Test
    fun a_partial_shelf_stays_due_for_a_refresh_after_reopening() = runTest {
        // Page 1 of the TV shelf was stored, page 2 never was, and page 1 is too thin to stop there: the shelf is
        // partial. Reopening Discover must still ask for the network, not treat the shelf as complete for six hours.
        val now = clock.now
        cache.saveGenres(MediaType.MOVIE, listOf(Genre("28", "Action")), now)
        cache.saveGenres(MediaType.TV, listOf(Genre("18", "Drama")), now)
        cache.savePopular(MediaType.MOVIE, 1, page(1, 1, movie("m1")), now)
        cache.savePopular(MediaType.TV, 1, page(1, 2, *tvTitles("keep", 5)), now)

        val reopened = feed.snapshot()

        assertEquals(false, reopened.popularTv?.complete, "the stored shelf is known to be partial")
        assertTrue(feed.needsRefresh(reopened), "a partial shelf is owed a refresh even while fresh")
    }

    @Test
    fun a_title_repeated_across_pages_is_shown_once() {
        val repeated = listOf(movie("1"), movie("1"), movie("2"))

        assertEquals(listOf(movie("1"), movie("2")), feed.visible(repeated, MutedGenres.NONE))
    }

    private fun movie(id: String, vararg genres: String) = MediaSummary(MediaId.tmdbMovie(id), id, genreIds = genres.toList())

    private fun show(id: String, vararg genres: String) = MediaSummary(MediaId.tmdbTv(id), id, genreIds = genres.toList())

    private fun tvTitles(prefix: String, count: Int, vararg genres: String): Array<MediaSummary> = Array(count) { show("$prefix-$it", *genres) }

    private fun page(number: Int, total: Int, vararg items: MediaSummary): PagedResult<MediaSummary> = PagedResult(items = items.toList(), page = number, totalPages = total)

    private class MutableClock(var now: Long) : AppClock {
        override fun nowEpochMs(): Long = now
        override fun utcOffsetMsAt(epochMs: Long): Long = 0L
    }

    private class MemoryDiscoverCache : DiscoverCache {
        private val genres = mutableMapOf<MediaType, Saved<List<Genre>>>()
        private val pages = mutableMapOf<Pair<MediaType, Int>, Saved<PagedResult<MediaSummary>>>()

        override suspend fun genres(type: MediaType): Saved<List<Genre>>? = genres[type]
        override suspend fun saveGenres(type: MediaType, genres: List<Genre>, fetchedAtEpochMs: Long) {
            this.genres[type] = Saved(genres, fetchedAtEpochMs)
        }
        override suspend fun popular(type: MediaType, page: Int): Saved<PagedResult<MediaSummary>>? = pages[type to page]
        override suspend fun savePopular(type: MediaType, page: Int, result: PagedResult<MediaSummary>, fetchedAtEpochMs: Long) {
            pages[type to page] = Saved(result, fetchedAtEpochMs)
        }
    }

    /** TMDB's stand-in: each list holds the answers for one type, one per page number. */
    @Test
    fun a_sparse_shelf_stops_at_the_page_budget_even_when_more_pages_exist() = runTest {
        // Every page is mostly muted and the catalogue has five more: the shelf still reads only MAX_SHELF_PAGES.
        repository.tvs = (1..5).map { n -> page(n, 5, *tvTitles("mute-$n", 15, "10767")) }

        feed.refresh(MutedGenres.NONE, MutedGenres(setOf(GenreKey(MediaType.TV, "10767"))))

        assertEquals((1..DiscoverFeed.MAX_SHELF_PAGES).toList(), repository.discoverPages(MediaType.TV), "no page after the budget is requested")
    }

    @Test
    fun a_failed_supplemental_page_keeps_the_saved_shelf_instead_of_replacing_it() = runTest {
        repository.tvs = listOf(page(1, 2, *tvTitles("mute-a", 15, "10767"), *tvTitles("keep-a", 5)), page(2, 2, *tvTitles("keep-b", 12)))
        feed.refresh(MutedGenres.NONE, MutedGenres(setOf(GenreKey(MediaType.TV, "10767"))))
        val saved = feed.snapshot()
        assertEquals(32, saved.popularTv?.items?.size, "the first refresh stored both pages")

        // Page 2 is gone this time: the fresh read is partial, and the stored shelf with every title stays.
        repository.tvs = listOf(page(1, 2, *tvTitles("mute-a", 15, "10767"), *tvTitles("keep-a", 5)))
        val refresh = feed.refresh(MutedGenres.NONE, MutedGenres(setOf(GenreKey(MediaType.TV, "10767"))))
        val merged = refresh.mergedInto(saved)

        assertTrue(refresh.anyFailed, "a partial shelf is reported as a failure, so the saved copy says so")
        assertEquals(32, merged.popularTv?.items?.size, "the stored shelf keeps the page that failed to refresh")
    }

    @Test
    fun a_partial_shelf_with_nothing_stored_is_kept_as_read() = runTest {
        repository.tvs = listOf(page(1, 2, *tvTitles("mute-a", 15, "10767"), *tvTitles("keep-a", 5)))

        val refresh = feed.refresh(MutedGenres.NONE, MutedGenres(setOf(GenreKey(MediaType.TV, "10767"))))
        val merged = refresh.mergedInto(DiscoverSnapshot(movieGenres = null, tvGenres = null, popularMovies = null, popularTv = null))

        assertEquals(20, merged.popularTv?.items?.size, "with no stored copy the partial read is all there is")
    }

    private class ScriptedRepository : SearchRepository {
        var movies: List<PagedResult<MediaSummary>> = emptyList()
        var tvs: List<PagedResult<MediaSummary>> = emptyList()
        var movieGenres: List<Genre> = emptyList()
        var tvGenres: List<Genre>? = emptyList()
        var failEverything = false
        private val requests = mutableListOf<Pair<MediaType, Int>>()

        fun discoverPages(type: MediaType): List<Int> = requests.filter { it.first == type }.map { it.second }

        override suspend fun discover(type: MediaType, page: Int, genreId: String?, excludedGenreIds: Set<String>): Result<PagedResult<MediaSummary>> {
            requests += type to page
            if (failEverything) return Result.failure(MetadataError.Offline())
            val source = if (type == MediaType.MOVIE) movies else tvs
            val answer = source.firstOrNull { it.page == page } ?: return Result.failure(MetadataError.NotFound())
            return Result.success(answer)
        }

        override suspend fun genres(type: MediaType): Result<List<Genre>> {
            if (failEverything) return Result.failure(MetadataError.Offline())
            val answer = if (type == MediaType.MOVIE) movieGenres else tvGenres
            return answer?.let { Result.success(it) } ?: Result.failure(MetadataError.Offline())
        }

        override suspend fun search(query: String, page: Int): Result<PagedResult<MediaSummary>> = error("not used")
        override suspend fun trending(): Result<List<MediaSummary>> = error("not used")
        override suspend fun details(id: MediaId): Result<MediaDetails> = error("not used")
        override suspend fun watchProviders(id: MediaId): Result<WatchProviders> = error("not used")
        override suspend fun recommendations(id: MediaId, page: Int): Result<PagedResult<MediaSummary>> = error("not used")
        override suspend fun similar(id: MediaId, page: Int): Result<PagedResult<MediaSummary>> = error("not used")
        override suspend fun episodeDetails(episodeId: EpisodeId): Result<EpisodeDetails> = error("not used")
    }
}
