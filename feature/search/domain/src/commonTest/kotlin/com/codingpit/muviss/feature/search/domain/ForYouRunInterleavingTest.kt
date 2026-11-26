package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.erase.DataEraseFence
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MutedGenres
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #965, round 2: a run captures its language, erase count, start time, seeds and pending force before anything
 * suspends, and nothing it stores may depend on what changed while it was waiting. Each test changes one captured
 * field at every suspension point of a For you run, and checks what the run stored:
 *
 *  - BEFORE_STORED_READ: after the run started, before its stored copy is read;
 *  - BEFORE_REQUEST: after that read, before TMDB is asked for a seed;
 *  - AFTER_REQUEST: after TMDB answered, before the answer is written.
 */
class ForYouRunInterleavingTest {

    private enum class Point { BEFORE_STORED_READ, BEFORE_REQUEST, AFTER_REQUEST }

    private class Clock(var now: Long) : AppClock {
        override fun nowEpochMs(): Long = now
        override fun utcOffsetMsAt(epochMs: Long): Long = 0L
    }

    /** Everything a run can read from the device, and every place a test can change it between suspensions. */
    private inner class World {
        val fence = DataEraseFence()
        val clock = Clock(NOW)
        var language = "en-US"
        var librarySeeds = listOf(SEED_A, SEED_B)
        var muted = false
        var tapped = false
        val hooks = mutableMapOf<Point, suspend () -> Unit>()

        /** The stored copies: key is seeds and language, as in the table. A write is dropped when its language has been left. */
        val copies = mutableMapOf<String, List<ForYouAnswer>>()
        val shelfWrites = mutableListOf<String>()

        /** The seeds TMDB was asked about, in order. */
        val asked = mutableListOf<MediaId>()

        suspend fun at(point: Point) {
            hooks[point]?.invoke()
        }

        val cache = object : DiscoverCache {
            override fun language(): String = language

            override suspend fun genres(type: MediaType, language: String): Saved<List<Genre>>? = null
            override suspend fun saveGenres(type: MediaType, genres: List<Genre>, fetchedAtEpochMs: Long, language: String) {
                if (language == this@World.language) shelfWrites += "genres:${type.wireName}:$language"
            }
            override suspend fun popular(type: MediaType, page: Int, language: String): Saved<PagedResult<MediaSummary>>? = null
            override suspend fun savePopular(type: MediaType, page: Int, result: PagedResult<MediaSummary>, fetchedAtEpochMs: Long, language: String) {
                if (language == this@World.language) shelfWrites += "popular:${type.wireName}:$language"
            }

            override suspend fun forYou(seeds: List<MediaId>, language: String): List<ForYouAnswer>? {
                at(Point.BEFORE_STORED_READ)
                return copies[key(seeds, language)]
            }

            override suspend fun saveForYou(seeds: List<MediaId>, answers: List<ForYouAnswer>, language: String) {
                // The language the device is in now: the same rule SqlDelightDiscoverCache applies to a late write.
                if (language != this@World.language) return
                copies[key(seeds, language)] = answers
            }
        }

        val feed = DiscoverFeed(NoShelves, cache, clock, fence)

        fun key(seeds: List<MediaId>, language: String) = seeds.map { it.toString() }.sorted().joinToString(",") + "|" + language

        /** One For you run as the view model starts it, with TMDB answering every seed. The hooks in [hooks] fire at its points. */
        suspend fun run(): List<ForYouAnswer> {
            val run = feed.startForYou(librarySeeds)
            val stored = feed.forYouStored(run)
            return feed.refreshForYou(run, stored) { seed ->
                at(Point.BEFORE_REQUEST)
                asked += seed
                at(Point.AFTER_REQUEST)
                Result.success(answerFor(seed))
            }.answers
        }
    }

    @Test
    fun a_language_change_at_any_point_stores_nothing_for_the_run() = runTest {
        for (point in Point.entries) {
            val world = World()
            world.hooks[point] = { world.language = "es-ES" }

            world.run()

            assertEquals(emptyMap(), world.copies, "a run that began in English must not file its answers under the Spanish key (change at $point)")
        }
    }

    @Test
    fun an_erase_at_any_point_stores_nothing_for_the_run() = runTest {
        for (point in Point.entries) {
            val world = World()
            world.hooks[point] = { world.fence.erase { world.copies.clear() } }

            world.run()

            assertEquals(emptyMap(), world.copies, "an answer requested before Delete all data is not stored after it (erase at $point)")
        }
    }

    @Test
    fun a_mute_change_at_any_point_leaves_the_stored_answers_raw() = runTest {
        for (point in Point.entries) {
            val world = World()
            world.hooks[point] = { world.muted = true }

            world.run()

            val stored = world.copies.values.single()
            assertEquals(listOf(answerFor(SEED_A).items, answerFor(SEED_B).items), stored.map { it.items }, "muting is applied at read, never stored (change at $point)")
        }
    }

    @Test
    fun a_clock_change_at_any_point_stamps_the_answers_with_the_moment_the_run_began() = runTest {
        for (point in Point.entries) {
            val world = World()
            world.hooks[point] = { world.clock.now += DiscoverFeed.TTL_MS * 2 }

            world.run()

            val stored = world.copies.values.single()
            assertTrue(stored.all { it.fetchedAtEpochMs == NOW }, "the answers are as old as the request that began at $NOW (change at $point)")
        }
    }

    @Test
    fun a_library_change_at_any_point_keeps_the_run_on_the_seeds_it_began_with() = runTest {
        for (point in Point.entries) {
            val world = World()
            world.hooks[point] = { world.librarySeeds = listOf(SEED_C) }

            world.run()

            assertEquals(setOf(world.key(listOf(SEED_A, SEED_B), "en-US")), world.copies.keys, "the copy is for the run's seeds only (change at $point)")
        }
    }

    /** Discover's shelves have the same captured run: a language change or an erase while they wait writes nothing. */
    @Test
    fun a_discover_refresh_straddling_a_language_change_writes_no_shelf_under_either_language() = runTest {
        val world = World()
        val repo = ScriptedShelves { world.language = "es-ES" }
        val feed = DiscoverFeed(repo, world.cache, world.clock, world.fence)

        feed.refresh(feed.startRun(), MutedGenres.NONE, MutedGenres.NONE)

        assertEquals(emptyList(), world.shelfWrites, "shelves fetched in English are not filed under Spanish, nor English after the change")
    }

    @Test
    fun a_discover_refresh_straddling_delete_all_data_writes_no_shelf() = runTest {
        val world = World()
        val repo = ScriptedShelves { world.fence.erase { } }
        val feed = DiscoverFeed(repo, world.cache, world.clock, world.fence)

        feed.refresh(feed.startRun(), MutedGenres.NONE, MutedGenres.NONE)

        assertEquals(emptyList(), world.shelfWrites, "a shelf requested before Delete all data is not written after it")
    }

    /** TMDB stand-in for the shelves: [afterRequest] runs between each request and its answer. */
    private class ScriptedShelves(private val afterRequest: suspend () -> Unit) : SearchRepository by NoShelves {
        override suspend fun genres(type: MediaType, fresh: Boolean): Result<List<Genre>> {
            afterRequest()
            return Result.success(listOf(Genre("28", "Action")))
        }

        override suspend fun discover(type: MediaType, page: Int, genreId: String?, excludedGenreIds: Set<String>, fresh: Boolean): Result<PagedResult<MediaSummary>> {
            afterRequest()
            return Result.success(PagedResult(listOf(MediaSummary(MediaId.tmdbMovie("m-$page"), "m")), page, 1))
        }
    }

    private companion object {
        const val NOW = 1_000_000_000L
        val SEED_A = MediaId.tmdbMovie("1")
        val SEED_B = MediaId.tmdbMovie("2")
        val SEED_C = MediaId.tmdbMovie("3")

        fun answerFor(seed: MediaId): PagedResult<MediaSummary> = PagedResult(listOf(MediaSummary(MediaId.tmdbMovie("r-${seed.external}"), "r-${seed.external}")), 1, 1)
    }

    private object NoShelves : SearchRepository {
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
