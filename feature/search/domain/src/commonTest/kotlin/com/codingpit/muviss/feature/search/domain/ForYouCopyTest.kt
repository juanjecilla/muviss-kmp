package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #785: "For you" keeps its copy per seed set, asks TMDB only for the seeds that are due, and keeps a failed seed's answer. */
class ForYouCopyTest {

    private val clock = MutableClock(1_000_000_000L)
    private val cache = MemoryForYouCache()
    private val feed = DiscoverFeed(NoShelvesRepository, cache, clock)

    private val seedA = MediaId.tmdbMovie("1")
    private val seedB = MediaId.tmdbMovie("2")
    private val seedC = MediaId.tmdbMovie("3")

    /** TMDB's stand-in for recommendations: one answer per seed, or a failure, and a log of which seeds were asked. */
    private inner class Recommendations {
        val answers = mutableMapOf<MediaId, Result<PagedResult<MediaSummary>>>()
        val asked = mutableListOf<MediaId>()

        suspend fun ask(seed: MediaId): Result<PagedResult<MediaSummary>> {
            asked += seed
            return answers[seed] ?: Result.failure(MetadataError.Offline())
        }

        fun answer(seed: MediaId, vararg titles: String) {
            answers[seed] = Result.success(PagedResult(titles.map { MediaSummary(MediaId.tmdbMovie(it), it) }, 1, 1))
        }
    }

    @Test
    fun a_new_seed_set_is_a_miss_and_never_shows_the_previous_sets_titles() = runTest {
        val recs = Recommendations().apply { answer(seedA, "10") }
        feed.refreshForYou(feed.startForYou(listOf(seedA)), stored = null, recs::ask)

        assertEquals(listOf(seedA), feed.forYouStored(feed.startForYou(listOf(seedA)))?.map { it.seed })
        assertNull(feed.forYouStored(feed.startForYou(listOf(seedA, seedB))), "adding a seed changes the set, so the copy for the old set is not read")
    }

    @Test
    fun the_seed_set_is_a_set_so_the_same_seeds_in_another_order_hit_the_copy() = runTest {
        val recs = Recommendations().apply {
            answer(seedA, "10")
            answer(seedB, "20")
        }
        feed.refreshForYou(feed.startForYou(listOf(seedA, seedB)), stored = null, recs::ask)

        val stored = feed.forYouStored(feed.startForYou(listOf(seedB, seedA)))

        assertEquals(listOf(seedB, seedA), stored?.map { it.seed }, "the copy follows the order the caller asks in")
    }

    @Test
    fun a_language_change_is_a_miss() = runTest {
        val recs = Recommendations().apply { answer(seedA, "10") }
        val englishRun = feed.startForYou(listOf(seedA))
        feed.refreshForYou(englishRun, stored = null, recs::ask)
        cache.language = "es-ES"

        assertNull(feed.forYouStored(feed.startForYou(listOf(seedA))), "a run that starts in Spanish misses the English copy")
        assertEquals(listOf(seedA), feed.forYouStored(englishRun)?.map { it.seed }, "a run that began in English still reads its English copy")
    }

    /** The answers a run wrote are filed under the language it began in, even when the device changed language while it waited. */
    @Test
    fun a_run_that_began_in_english_files_its_answers_under_english_after_the_language_changes() = runTest {
        val recs = Recommendations().apply { answer(seedA, "10") }
        val englishRun = feed.startForYou(listOf(seedA))
        cache.language = "es-ES"

        feed.refreshForYou(englishRun, stored = null, recs::ask)

        assertNull(cache.forYou(listOf(seedA), "es-ES"), "nothing is filed under the Spanish key")
        assertEquals(listOf(seedA), cache.forYou(listOf(seedA), "en-US")?.map { it.seed }, "the answers sit under the language of the run")
    }

    @Test
    fun a_fresh_copy_asks_no_seed_at_all() = runTest {
        val recs = Recommendations().apply {
            answer(seedA, "10")
            answer(seedB, "20")
        }
        val seeds = listOf(seedA, seedB)
        feed.refreshForYou(feed.startForYou(seeds), stored = null, recs::ask)
        recs.asked.clear()

        val answers = feed.refreshForYou(feed.startForYou(seeds), feed.forYouStored(feed.startForYou(seeds)), recs::ask)

        assertEquals(emptyList(), recs.asked, "a library change that keeps the seed set costs no request")
        assertEquals(2, answers.size)
    }

    @Test
    fun only_the_seed_past_its_ttl_is_asked_again() = runTest {
        val seeds = listOf(seedA, seedB)
        val recs = Recommendations().apply { answer(seedA, "10") }
        // B fails on the first load, so the copy holds A alone for this seed set.
        feed.refreshForYou(feed.startForYou(seeds), stored = null, recs::ask)
        recs.asked.clear()
        clock.now += DiscoverFeed.TTL_MS / 2
        recs.answer(seedB, "20")
        // B is missing, so it is asked half a TTL after A was fetched; A is still fresh and is not asked.
        feed.refreshForYou(feed.startForYou(seeds), feed.forYouStored(feed.startForYou(seeds)), recs::ask)
        assertEquals(listOf(seedB), recs.asked, "only the missing seed is asked")
        recs.asked.clear()

        // A is now past its TTL and B is not yet: only A is due.
        clock.now += DiscoverFeed.TTL_MS / 2 + 1
        val answers = feed.refreshForYou(feed.startForYou(seeds), feed.forYouStored(feed.startForYou(seeds)), recs::ask)

        assertEquals(listOf(seedA), recs.asked, "B was fetched half a TTL ago, so it is not due yet")
        assertEquals(listOf("10"), answers.first { it.seed == seedA }.items.map { it.id.external })
        assertEquals(listOf("20"), answers.first { it.seed == seedB }.items.map { it.id.external })
    }

    @Test
    fun a_failed_seed_keeps_its_stored_answer_and_stays_due() = runTest {
        val recs = Recommendations().apply {
            answer(seedA, "10")
            answer(seedB, "20")
        }
        val seeds = listOf(seedA, seedB)
        feed.refreshForYou(feed.startForYou(seeds), stored = null, recs::ask)
        val firstFetch = clock.now
        clock.now += DiscoverFeed.TTL_MS + 1
        recs.answers.remove(seedB)

        val answers = feed.refreshForYou(feed.startForYou(seeds), feed.forYouStored(feed.startForYou(seeds)), recs::ask)

        assertEquals(listOf("10"), answers.first { it.seed == seedA }.items.map { it.id.external }, "A was refreshed")
        assertEquals(listOf("20"), answers.first { it.seed == seedB }.items.map { it.id.external }, "B failed and keeps its rows")
        assertEquals(firstFetch, answers.first { it.seed == seedB }.fetchedAtEpochMs, "B keeps its old fetch time, so the next load asks again")

        recs.asked.clear()
        feed.refreshForYou(feed.startForYou(seeds), feed.forYouStored(feed.startForYou(seeds)), recs::ask)
        assertEquals(listOf(seedB), recs.asked, "B is still due on the next load")
    }

    @Test
    fun a_seed_that_fails_with_nothing_stored_contributes_nothing_and_is_not_a_crash() = runTest {
        val recs = Recommendations().apply { answer(seedA, "10") }

        val answers = feed.refreshForYou(feed.startForYou(listOf(seedA, seedC)), stored = null, recs::ask)

        assertEquals(listOf(seedA), answers.map { it.seed })
        assertEquals(listOf(seedA), feed.forYouStored(feed.startForYou(listOf(seedA, seedC)))?.map { it.seed }, "the seed that answered is stored")
    }

    @Test
    fun a_refresh_where_every_seed_fails_stores_nothing_new() = runTest {
        val recs = Recommendations()
        val answers = feed.refreshForYou(feed.startForYou(listOf(seedA)), stored = null, recs::ask)

        assertTrue(answers.isEmpty())
        assertNull(feed.forYouStored(feed.startForYou(listOf(seedA))), "a failure is not an empty answer: nothing is written")
    }

    @Test
    fun a_stored_answer_that_is_empty_is_a_real_answer_and_replaces_the_old_one() = runTest {
        val recs = Recommendations().apply { answer(seedA, "10") }
        feed.refreshForYou(feed.startForYou(listOf(seedA)), stored = null, recs::ask)
        clock.now += DiscoverFeed.TTL_MS + 1
        recs.answer(seedA)

        val answers = feed.refreshForYou(feed.startForYou(listOf(seedA)), feed.forYouStored(feed.startForYou(listOf(seedA))), recs::ask)

        assertEquals(emptyList(), answers.single().items, "a successful empty list is the answer TMDB gave, not a failure")
    }

    private class MutableClock(var now: Long) : AppClock {
        override fun nowEpochMs(): Long = now
        override fun utcOffsetMsAt(epochMs: Long): Long = 0L
    }

    /**
     * Keeps the seed sets in a map, the way the table keeps one row per set. The key is the language the caller passes
     * in, never the field: the field is only what the device is in now ([language]), which a run reads once, when it starts.
     */
    private class MemoryForYouCache : DiscoverCache {
        var language = "en-US"
        private val copies = mutableMapOf<String, List<ForYouAnswer>>()

        override fun language(): String = language

        private fun key(seeds: List<MediaId>, language: String) = seeds.map { it.toString() }.sorted().joinToString(",") + "|" + language

        override suspend fun forYou(seeds: List<MediaId>, language: String): List<ForYouAnswer>? = copies[key(seeds, language)]

        override suspend fun saveForYou(seeds: List<MediaId>, answers: List<ForYouAnswer>, language: String) {
            copies[key(seeds, language)] = answers
        }

        // The shelves are not under test here.
        override suspend fun genres(type: MediaType, language: String): Saved<List<Genre>>? = null
        override suspend fun saveGenres(type: MediaType, genres: List<Genre>, fetchedAtEpochMs: Long, language: String) = Unit
        override suspend fun popular(type: MediaType, page: Int, language: String): Saved<PagedResult<MediaSummary>>? = null
        override suspend fun savePopular(type: MediaType, page: Int, result: PagedResult<MediaSummary>, fetchedAtEpochMs: Long, language: String) = Unit
    }

    /** The shelves are not under test here. */
    private object NoShelvesRepository : SearchRepository {
        override suspend fun search(query: String, page: Int): Result<PagedResult<MediaSummary>> = error("not used")
        override suspend fun trending(): Result<List<MediaSummary>> = error("not used")
        override suspend fun details(id: MediaId): Result<MediaDetails> = error("not used")
        override suspend fun discover(type: MediaType, page: Int, genreId: String?, excludedGenreIds: Set<String>, fresh: Boolean): Result<PagedResult<MediaSummary>> = error("not used")
        override suspend fun genres(type: MediaType, fresh: Boolean): Result<List<Genre>> = error("not used")
        override suspend fun watchProviders(id: MediaId): Result<WatchProviders> = error("not used")
        override suspend fun recommendations(id: MediaId, page: Int, fresh: Boolean): Result<PagedResult<MediaSummary>> = error("not used")
        override suspend fun similar(id: MediaId, page: Int, fresh: Boolean): Result<PagedResult<MediaSummary>> = error("not used")
        override suspend fun episodeDetails(episodeId: EpisodeId): Result<EpisodeDetails> = error("not used")
    }
}
