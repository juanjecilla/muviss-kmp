@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.search.domain.DiscoverCache
import com.codingpit.muviss.feature.search.domain.DiscoverFeed
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.ForYouAnswer
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.Saved
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.feature.search.domain.SearchRepository
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
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The saved-copy mark (#974) as a state machine. Each row is one path through it, written as the person would take it:
 * a Retry or Refresh, a muting change (automatic), a query typed and cleared, a request held and then released.
 * Discover and For you each keep their own mark; the screen shows the two combined. [discoverOnline] and [forYouOnline]
 * decide what each request answers when released, so a row can make one side fail while the other succeeds.
 *
 * Every row starts from a fresh copy inside its TTL, with the library holding one seed, so nothing is asked until a
 * row asks for it.
 */
class SavedMarkTransitionsTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_saved_mark_follows_every_row_of_the_table() = runTest {
        // Every row runs, so one broken transition does not hide the others; the failures are reported together.
        val failures = TABLE.mapNotNull { row ->
            try {
                Scenario(this).apply(row.steps)
                null
            } catch (e: AssertionError) {
                "${row.name}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n"))
    }

    private data class Row(val name: String, val steps: Scenario.() -> Unit)

    private companion object {
        val TABLE = listOf(
            // A user-requested refresh that fails inside the TTL says so; an automatic one stays silent.
            Row("a Retry that fails inside the TTL says so") {
                discoverOffline()
                retry()
                settle()
                expectBanner(true)
            },
            Row("an automatic failure alone stays silent") {
                discoverOffline()
                muteToggle()
                settle()
                expectBanner(false)
            },
            Row("an automatic failure after a Retry keeps the mark") {
                discoverOffline()
                retry()
                settle()
                muteToggle()
                settle()
                expectBanner(true)
            },
            Row("a Retry that answers in full clears the mark") {
                discoverOffline()
                retry()
                settle()
                online()
                retry()
                settle()
                expectBanner(false)
            },
            Row("an automatic success clears a Retry's failure") {
                discoverOffline()
                retry()
                settle()
                online()
                muteToggle()
                settle()
                expectBanner(false)
            },
            // Held: the mark stays while the request is in flight, and the old failure is not cleared on the way.
            Row("a held Retry keeps the mark while its request is in flight") {
                discoverOffline()
                retry()
                settle()
                hold()
                retry()
                settle()
                expectBanner(true)
            },
            Row("a held Retry cancelled by a query typed and cleared keeps the mark") {
                discoverOffline()
                retry()
                settle()
                hold()
                retry()
                settle()
                typeQuery()
                clearQuery()
                settle()
                expectBanner(true)
            },
            Row("a held Retry that then answers clears the mark") {
                discoverOffline()
                retry()
                settle()
                hold()
                retry()
                settle()
                online()
                release()
                settle()
                expectBanner(false)
            },
            Row("a held Retry that then fails keeps the mark") {
                discoverOffline()
                retry()
                settle()
                hold()
                retry()
                settle()
                release()
                settle()
                expectBanner(true)
            },
            Row("a held automatic refresh cancelled by a query keeps a Retry's failure") {
                discoverOffline()
                retry()
                settle()
                hold()
                muteToggle()
                settle()
                typeQuery()
                clearQuery()
                settle()
                expectBanner(true)
            },
            Row("a query typed over a held Retry keeps the mark") {
                discoverOffline()
                retry()
                settle()
                hold()
                retry()
                settle()
                typeQuery()
                settle()
                expectBanner(true)
            },
            // Discover and For you keep separate marks. A success on one side never clears the other's failure.
            Row("Discover answering does not clear a For you failure") {
                only(discover = true, forYou = false)
                retry()
                settle()
                expectBanner(true)
            },
            Row("For you answering does not clear a Discover failure") {
                only(discover = false, forYou = true)
                retry()
                settle()
                expectBanner(true)
            },
            Row("a For you failure outlives an automatic Discover success") {
                only(discover = true, forYou = false)
                retry()
                settle()
                muteToggle()
                settle()
                expectBanner(true)
            },
            Row("an automatic For you success clears its own failure") {
                only(discover = true, forYou = false)
                retry()
                settle()
                only(discover = true, forYou = true)
                muteToggle()
                settle()
                expectBanner(false)
            },
            Row("a held For you request cancelled by a saved title keeps its failure") {
                only(discover = true, forYou = false)
                retry()
                settle()
                hold()
                retry()
                settle()
                addSavedTitle()
                settle()
                expectBanner(true)
            },
        )
    }

    /** One view model, its copy and TMDB, with the requests answering as the row says. */
    private class Scenario(private val scope: TestScope) {
        var discoverOnline = false
        var forYouOnline = false

        /** While set, every request waits on it: a request "held" over the screen. */
        var gate: CompletableDeferred<Unit>? = null

        private val seed = MediaId.tmdbMovie("1")
        private val talk = GenreKey(MediaType.TV, "10767")
        private val recommended = MediaSummary(MediaId.tmdbMovie("50"), "Recommended")
        private val movie = MediaSummary(MediaId.tmdbMovie("10"), "Movie")
        private val show = MediaSummary(MediaId.tmdbTv("20"), "Show")

        private val cache = ScenarioCache(seed, recommended, movie, show)
        private val library = FakeSearchCollectionApi(
            listOf(CollectionSummary(seed, "Saved", posterUrl = null, status = WatchStatus.NOT_STARTED, favorite = true, addedAtEpochMs = 0L)),
        )
        private val settings = FakeMutingApi()
        private val repo = ScenarioRepo(this, movie, show, recommended)
        private val vm = SearchViewModel(
            SearchMediaUseCase(repo),
            DiscoverMediaUseCase(repo),
            RecommendationsUseCase(repo),
            library,
            FakeTriageApi(),
            settings,
            DiscoverFeed(repo, cache, FakeClock(epochMs = 1_000L)),
            connectivity = ConnectivityMonitor.AlwaysOnline,
        )

        // Discover fails and For you answers, so each side's mark is seen on its own.
        fun discoverOffline() = only(discover = false, forYou = true)
        fun online() = only(discover = true, forYou = true)
        fun only(discover: Boolean, forYou: Boolean) {
            discoverOnline = discover
            forYouOnline = forYou
        }

        fun retry() = vm.retry()
        fun hold() {
            gate = CompletableDeferred()
        }
        fun release() {
            gate?.complete(Unit)
            gate = null
        }
        fun muteToggle() {
            settings.configured.value = if (settings.configured.value == MutedGenres.NONE) MutedGenres(setOf(talk)) else MutedGenres.NONE
        }

        /** A title saved without changing the seed set: the For you run in flight is cancelled and begins again. */
        fun addSavedTitle() {
            library.library.value = library.library.value + CollectionSummary(MediaId.tmdbMovie("77"), "Other", posterUrl = null, status = WatchStatus.NOT_STARTED)
        }
        fun typeQuery() = vm.onQueryChange("late")
        fun clearQuery() = vm.onQueryChange("")
        fun settle() = scope.advanceUntilIdle()

        fun expectBanner(expected: Boolean) {
            assertEquals(expected, vm.state.value.discoverSaved, "the saved mark")
        }
    }

    private class ScenarioRepo(
        private val scenario: Scenario,
        private val movie: MediaSummary,
        private val show: MediaSummary,
        private val recommended: MediaSummary,
    ) : SearchRepository {
        private suspend fun waitIfHeld() {
            scenario.gate?.await()
        }

        override suspend fun search(query: String, page: Int) = Result.success(PagedResult(emptyList<MediaSummary>(), page, page))
        override suspend fun trending() = Result.success(emptyList<MediaSummary>())
        override suspend fun details(id: MediaId) = Result.success(MediaDetails(MediaSummary(id, "t")))

        override suspend fun discover(type: MediaType, page: Int, genreId: String?, excludedGenreIds: Set<String>, fresh: Boolean): Result<PagedResult<MediaSummary>> {
            waitIfHeld()
            if (!scenario.discoverOnline) return Result.failure(MetadataError.Offline())
            return Result.success(PagedResult(listOf(if (type == MediaType.MOVIE) movie else show), page, page))
        }

        override suspend fun genres(type: MediaType, fresh: Boolean): Result<List<Genre>> {
            waitIfHeld()
            if (!scenario.discoverOnline) return Result.failure(MetadataError.Offline())
            return Result.success(listOf(Genre("28", "Action")))
        }

        override suspend fun watchProviders(id: MediaId) = Result.success(WatchProviders())

        override suspend fun recommendations(id: MediaId, page: Int, fresh: Boolean): Result<PagedResult<MediaSummary>> {
            waitIfHeld()
            if (!scenario.forYouOnline) return Result.failure(MetadataError.Offline())
            return Result.success(PagedResult(listOf(recommended), 1, 1))
        }

        override suspend fun similar(id: MediaId, page: Int, fresh: Boolean) = Result.success(PagedResult(emptyList<MediaSummary>(), 1, 1))
        override suspend fun episodeDetails(episodeId: EpisodeId): Result<EpisodeDetails> = Result.failure(MetadataError.Offline())
    }

    /** A copy from before the test: fetched at 0, read at 1_000, so it is inside its TTL for every row. */
    private class ScenarioCache(seed: MediaId, recommended: MediaSummary, movie: MediaSummary, show: MediaSummary) : DiscoverCache {
        private val genres = mutableMapOf<Pair<MediaType, String>, Saved<List<Genre>>>()
        private val popular = mutableMapOf<Triple<MediaType, Int, String>, Saved<PagedResult<MediaSummary>>>()
        private val forYou = mutableMapOf<String, List<ForYouAnswer>>()

        init {
            for (type in listOf(MediaType.MOVIE, MediaType.TV)) {
                genres[type to "en-US"] = Saved(listOf(Genre("28", "Action")), fetchedAtEpochMs = 0L)
                popular[Triple(type, 1, "en-US")] = Saved(
                    PagedResult(listOf(if (type == MediaType.MOVIE) movie else show), 1, 1),
                    fetchedAtEpochMs = 0L,
                )
            }
            forYou[key(listOf(seed), "en-US")] = listOf(ForYouAnswer(seed, listOf(recommended), fetchedAtEpochMs = 0L))
        }

        private fun key(seeds: List<MediaId>, language: String) = seeds.joinToString(",") + "|" + language

        override fun language(): String = "en-US"
        override suspend fun genres(type: MediaType, language: String): Saved<List<Genre>>? = genres[type to language]
        override suspend fun saveGenres(type: MediaType, genres: List<Genre>, fetchedAtEpochMs: Long, language: String) {
            this.genres[type to language] = Saved(genres, fetchedAtEpochMs)
        }
        override suspend fun popular(type: MediaType, page: Int, language: String): Saved<PagedResult<MediaSummary>>? = popular[Triple(type, page, language)]
        override suspend fun savePopular(type: MediaType, page: Int, result: PagedResult<MediaSummary>, fetchedAtEpochMs: Long, language: String) {
            popular[Triple(type, page, language)] = Saved(result, fetchedAtEpochMs)
        }
        override suspend fun forYou(seeds: List<MediaId>, language: String): List<ForYouAnswer>? = forYou[key(seeds, language)]
        override suspend fun saveForYou(seeds: List<MediaId>, answers: List<ForYouAnswer>, language: String) {
            forYou[key(seeds, language)] = answers
        }
    }
}
