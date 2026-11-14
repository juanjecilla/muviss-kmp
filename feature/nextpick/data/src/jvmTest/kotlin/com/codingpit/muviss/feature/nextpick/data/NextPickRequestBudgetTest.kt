package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.network.NotFoundTitles
import com.codingpit.muviss.core.network.WatchProviderFill
import com.codingpit.muviss.core.network.WatchProviderLookup
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.nextpick.api.PickAnchor
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.api.SessionLength
import com.codingpit.muviss.feature.nextpick.domain.PickAnswer
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.nextpick.domain.PickEngine
import com.codingpit.muviss.feature.nextpick.domain.PickExclusions
import com.codingpit.muviss.feature.nextpick.domain.PickMuting
import com.codingpit.muviss.feature.nextpick.domain.PickResult
import com.codingpit.muviss.feature.nextpick.domain.PickSession
import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteKind
import com.codingpit.muviss.feature.settings.api.TasteProfileApi
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.MutedGenres
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EPIC 51 (#399, #548): what one Pick Session may cost in TMDB requests. The session is the real domain
 * [PickSession], the engine is the real [PickEngine] with the real sources, and only TMDB and the library are
 * doubles. The counts are exact on purpose: a source that starts fetching more shows up here as a changed number,
 * not as a slower screen months later.
 *
 * One pick is one gather, so the count is per pick. "Another" is served from that gather by the ViewModel and is
 * pinned in `PickSessionViewModelTest`, not here.
 */
class NextPickRequestBudgetTest {

    private val finished = MediaId.tmdbTv("1399")
    private val netflix = TasteItem(TasteKind.SERVICE, "8", "Netflix")
    private val clock: AppClock = fixedClock

    private val library = FakeLibrary(listOf(libraryTitle(movie("1"), WatchStatus.NOT_STARTED), libraryTitle(show("1399"), WatchStatus.FINISHED)))

    /**
     * [servicesKnown] must be true for "only my services" to be asked at all: without it the session never offers
     * the question, the answer is dropped, and the filter does not run. A test of the filter that forgets this
     * passes vacuously, so the services tests say it explicitly.
     */
    private fun standard(anchored: Boolean = true, online: Boolean = true, servicesKnown: Boolean = false) = PickContext(
        length = SessionLength.STANDARD,
        online = online,
        hasBacklog = true,
        hasWatchedTitles = true,
        servicesKnown = servicesKnown,
        anchorCandidate = if (anchored) finished else null,
    )

    /** The criteria a session ends with when the person answers [answers] and skips every other question. */
    private fun criteriaFor(context: PickContext, vararg answers: PickAnswer): PickCriteria {
        val byQuestion = answers.associateBy { it.question }
        var session = PickSession.start(context)
        while (true) {
            val question = session.question ?: break
            session = byQuestion[question]?.let { session.answer(it) } ?: session.skip()
        }
        return session.criteria
    }

    /** Wired as NextPickDataModule wires it, so the genre catalogue's requests are counted too (#547). */
    private fun genreIdsOver(metadata: CountingMetadata) = LibraryGenreIds(liveCatalogue = { metadata.genres(it) }, language = { "en-US" })

    @Suppress("LongParameterList") // one defaulted knob per thing a test varies, as DetailViewModelTest.viewModel does
    private suspend fun pick(
        metadata: CountingMetadata,
        context: PickContext,
        vararg answers: PickAnswer,
        genreIds: LibraryGenreIds = genreIdsOver(metadata),
        services: OwnServices = OwnServices(),
        library: FakeLibrary = this.library,
        criteria: PickCriteria? = null,
    ): PickResult {
        val collection: () -> CollectionApi = { library }
        val engine = PickEngine(
            sources = listOf(
                OwnServicesCandidates(
                    inner = CombinedCandidates(
                        listOf(CollectionBacklogCandidates(collection, genreIds, clock), AnchorCandidates(metadata, collection), SeedCandidates(metadata, collection)),
                    ),
                    taste = { services.taste },
                    lookup = { services.lookup },
                    fill = { services.fill },
                    region = { "US" },
                    missing = { NotFoundTitles(clock) },
                ),
                DiscoverSource(metadata, collection, thisYear = { 2027 }, muting = PickMuting { MutedGenres.NONE }, taste = { services.taste }, region = { "US" }),
            ),
            exclusions = PickExclusions { emptySet() },
            history = TasteHistoryReader(CollectionHistoryReader(clock, collection, connectivity(context.online), genreIds), { services.taste }),
            muting = PickMuting { MutedGenres.NONE },
        )
        return engine.pick(criteria ?: criteriaFor(context, *answers), context)
    }

    @Test
    fun `a_liked_anchor_online_costs_two_discover_pages_and_one_each_of_recommendations_and_similar`() = runTest {
        val metadata = CountingMetadata(discover = mapOf(MediaType.MOVIE to listOf(movie("2")), MediaType.TV to listOf(show("3"))))

        pick(metadata, standard(), PickAnswer.Anchor(PickAnchor(finished, like = true)))

        // The genre lists, one per format the library holds, are read once per process (see below).
        assertEquals(mapOf("genres" to 2, "discover" to 2, "recommendations" to 1, "similar" to 1), metadata.requests)
        assertEquals(6, metadata.total)
    }

    @Test
    fun `something_different_fetches_no_recommendations_because_the_engine_would_only_penalise_them`() = runTest {
        val metadata = CountingMetadata(discover = mapOf(MediaType.MOVIE to listOf(movie("2"))))

        pick(metadata, standard(), PickAnswer.Anchor(PickAnchor(finished, like = false)))

        assertEquals(mapOf("genres" to 2, "discover" to 2), metadata.requests)
    }

    @Test
    fun `a_format_other_than_the_anchors_fetches_no_recommendations_and_one_discover_page`() = runTest {
        // The anchor is a show. A movie pick cannot be "more like" it through TMDB's show recommendations.
        val metadata = CountingMetadata(discover = mapOf(MediaType.MOVIE to listOf(movie("2"))))

        pick(metadata, standard(), PickAnswer.Anchor(PickAnchor(finished, like = true)), PickAnswer.FormatAndTime(MediaType.MOVIE, null))

        assertEquals(mapOf("genres" to 2, "discover" to 1), metadata.requests)
    }

    @Test
    fun `something_new_without_an_anchor_is_discover_only`() = runTest {
        val metadata = CountingMetadata(discover = mapOf(MediaType.MOVIE to listOf(movie("2")), MediaType.TV to listOf(show("3"))))

        pick(metadata, standard(anchored = false), PickAnswer.Source(PickSource.SOMETHING_NEW))

        assertEquals(mapOf("discover" to 2), metadata.requests)
    }

    @Test
    fun `something_new_without_an_anchor_asks_tmdb_about_at_most_three_taste_seeds`() = runTest {
        // Four favourites: "For you" seeds from at most three, and so does a no-anchor session (#661).
        val favourites = FakeLibrary(listOf("11", "12", "13", "14").map { libraryTitle(movie(it), WatchStatus.NOT_STARTED).copy(favorite = true) })
        val metadata = CountingMetadata(discover = mapOf(MediaType.MOVIE to listOf(movie("2"))), recommended = listOf(movie("20")), similarTitles = listOf(show("21")))

        pick(metadata, standard(anchored = false), PickAnswer.Source(PickSource.SOMETHING_NEW), library = favourites)

        assertEquals(3, metadata.requests["recommendations"])
        assertEquals(3, metadata.requests["similar"])
    }

    @Test
    fun `a_movie_session_seeds_from_its_own_format_even_when_newer_favourites_are_shows`() = runTest {
        // Three newer favourites are shows and the one older favourite is a movie. The seed cap must count movies only,
        // or the session takes the three shows, drops them all, and asks TMDB about nothing.
        val library = FakeLibrary(
            listOf(
                libraryTitle(show("31"), WatchStatus.NOT_STARTED).copy(favorite = true, addedAtEpochMs = 300L),
                libraryTitle(show("32"), WatchStatus.NOT_STARTED).copy(favorite = true, addedAtEpochMs = 200L),
                libraryTitle(show("33"), WatchStatus.NOT_STARTED).copy(favorite = true, addedAtEpochMs = 100L),
                libraryTitle(movie("11"), WatchStatus.NOT_STARTED).copy(favorite = true, addedAtEpochMs = 10L),
            ),
        )
        val metadata = CountingMetadata(discover = mapOf(MediaType.MOVIE to listOf(movie("2"))), recommended = listOf(movie("20")), similarTitles = listOf(movie("21")))

        pick(metadata, standard(anchored = false), PickAnswer.Source(PickSource.SOMETHING_NEW), PickAnswer.FormatAndTime(MediaType.MOVIE, null), library = library)

        assertEquals(1, metadata.requests["recommendations"])
        assertEquals(1, metadata.requests["similar"])
    }

    @Test
    fun `offline_something_new_makes_no_seed_requests`() = runTest {
        val favourites = FakeLibrary(listOf(libraryTitle(movie("11"), WatchStatus.NOT_STARTED).copy(favorite = true)))
        val metadata = CountingMetadata()

        pick(metadata, standard(anchored = false, online = false), PickAnswer.Source(PickSource.SOMETHING_NEW), library = favourites)

        assertEquals(0, metadata.total)
    }

    @Test
    fun `my_list_costs_only_the_genre_lists_and_only_on_the_first_pick`() = runTest {
        val metadata = CountingMetadata()
        val genreIds = genreIdsOver(metadata)
        val myList = arrayOf(PickAnswer.Source(PickSource.MY_LIST), PickAnswer.Anchor(PickAnchor(finished, like = true)))

        val result = pick(metadata, standard(), *myList, genreIds = genreIds)
        pick(metadata, standard(), *myList, genreIds = genreIds)

        // Mapping the library's genre names to ids (#547) reads each format's list once; no catalogue is asked.
        assertEquals(mapOf("genres" to 2), metadata.requests)
        assertTrue(result.picks.all { it.candidate.summary.id == MediaId.tmdbMovie("1") }, "my list is the backlog only")
    }

    @Test
    fun `only_my_services_fills_no_more_than_the_cap_of_uncached_titles_in_one_pick`() = runTest {
        // #762: 25 backlog titles and none cached. The pick asks TMDB about the cap, and about nothing beyond it.
        assertEquals(10, MAX_PROVIDER_FILLS_PER_PICK, "the cap is pinned here on purpose: change it deliberately")
        val metadata = CountingMetadata(flatrateOf = { setOf("8") })
        val cache = MutableWatchCache()
        val fill = MetadataWatchFill(metadata, cache)
        val library = FakeLibrary((1..25).map { libraryTitle(movie("$it"), WatchStatus.NOT_STARTED) })

        pick(
            metadata,
            standard(servicesKnown = true, anchored = false),
            PickAnswer.EraAndServices(null, null, onlyMyServices = true),
            library = library,
            services = OwnServices(FakeTasteProfile(listOf(netflix)), cache, fill),
        )

        assertEquals(MAX_PROVIDER_FILLS_PER_PICK, metadata.requests["watch"])
        assertEquals(MAX_PROVIDER_FILLS_PER_PICK, fill.filled.size)
        assertEquals((1..10).map { MediaId.tmdbMovie("$it") }, fill.filled, "the first titles in library order, no more")
    }

    @Test
    fun `the_cap_is_shared_by_the_backlog_and_the_anchor_so_a_pick_never_pays_once_per_source`() = runTest {
        // Six uncached backlog titles and eight uncached recommendations: the pool holds fourteen, the pick pays for ten.
        val recommended = (20..27).map { movie("$it") }
        val metadata = CountingMetadata(recommended = recommended, flatrateOf = { setOf("8") })
        val cache = MutableWatchCache()
        val fill = MetadataWatchFill(metadata, cache)
        val library = FakeLibrary((1..6).map { libraryTitle(movie("$it"), WatchStatus.NOT_STARTED) })

        // The anchor question takes one of the standard session's four questions, which leaves no room for the services
        // question. The criteria are handed in so this test is about the budget alone (the session's order is pinned elsewhere).
        pick(
            metadata,
            standard(servicesKnown = true),
            library = library,
            services = OwnServices(FakeTasteProfile(listOf(netflix)), cache, fill),
            criteria = PickCriteria(anchor = PickAnchor(finished, like = true), onlyMyServices = true),
        )

        assertEquals(MAX_PROVIDER_FILLS_PER_PICK, metadata.requests["watch"], "one budget across the sources, not one per source")
        assertEquals((1..6).map { MediaId.tmdbMovie("$it") } + (20..23).map { MediaId.tmdbMovie("$it") }, fill.filled, "backlog first, then as much of the recommendations as the rest of the cap allows")
    }

    @Test
    fun `successive_picks_reach_further_into_the_library_and_never_ask_again_about_a_title_already_cached`() = runTest {
        val metadata = CountingMetadata(flatrateOf = { setOf("8") })
        val cache = MutableWatchCache()
        val fill = MetadataWatchFill(metadata, cache)
        val services = OwnServices(FakeTasteProfile(listOf(netflix)), cache, fill)
        val library = FakeLibrary((1..25).map { libraryTitle(movie("$it"), WatchStatus.NOT_STARTED) })
        val answers = arrayOf(PickAnswer.EraAndServices(null, null, onlyMyServices = true))

        pick(metadata, standard(servicesKnown = true, anchored = false), *answers, library = library, services = services)
        pick(metadata, standard(servicesKnown = true, anchored = false), *answers, library = library, services = services)

        assertEquals(20, metadata.requests["watch"])
        assertEquals((1..20).map { MediaId.tmdbMovie("$it") }, fill.filled, "titles 11 to 20 on the second pick, none twice")
    }

    @Test
    fun `offline_only_my_services_asks_tmdb_nothing_and_judges_the_library_on_its_cache`() = runTest {
        // The services question is not offered offline, so the flag comes from an answer given before the connection
        // dropped. The criteria are handed in directly; the session cannot produce this state offline.
        val metadata = CountingMetadata(flatrateOf = { setOf("8") })
        val cache = MutableWatchCache().apply { entries[MediaId.tmdbMovie("1")] = "US" to setOf("8") }
        val fill = MetadataWatchFill(metadata, cache)
        val library = FakeLibrary((1..3).map { libraryTitle(movie("$it"), WatchStatus.NOT_STARTED) })

        val result = pick(
            metadata,
            standard(servicesKnown = true, online = false),
            library = library,
            services = OwnServices(FakeTasteProfile(listOf(netflix)), cache, fill),
            criteria = PickCriteria(onlyMyServices = true),
        )

        assertEquals(0, metadata.total)
        assertTrue(fill.filled.isEmpty())
        assertTrue(result.offline)
        assertEquals(listOf(MediaId.tmdbMovie("1")), result.picks.map { it.candidate.id }, "cached and on my service; the other two are unknown and not claimed")
    }

    @Test
    fun `offline_costs_no_tmdb_requests_and_the_pick_comes_from_the_library`() = runTest {
        val metadata = CountingMetadata()

        val result = pick(metadata, standard(online = false))

        assertEquals(0, metadata.total)
        assertTrue(result.offline)
        assertEquals(listOf(MediaId.tmdbMovie("1")), result.picks.map { it.candidate.id })
    }

    @Test
    fun `a_failed_recommendations_call_still_gets_the_similar_titles_and_is_not_retried_by_the_engine`() = runTest {
        val similar = show("8")
        val metadata = CountingMetadata(similarTitles = listOf(similar), failRecommendations = MetadataError.Offline())

        val result = pick(metadata, standard(), PickAnswer.Anchor(PickAnchor(finished, like = true)))

        assertEquals(1, metadata.requests["recommendations"])
        assertEquals(1, metadata.requests["similar"])
        assertTrue(result.picks.any { it.candidate.id == similar.id }, "the similar title survives the recommendations failure")
    }
}

/** What "only my services" reads and may fill: the Taste Profile's services, the cache, and the fill that writes it. */
private data class OwnServices(
    val taste: TasteProfileApi = FakeTasteProfile(),
    val lookup: WatchProviderLookup = NoWatchLookup,
    val fill: WatchProviderFill = NoWatchFill,
)

private object NoWatchLookup : WatchProviderLookup {
    override suspend fun cachedFlatrateIds(mediaIds: Set<MediaId>, region: String): Map<MediaId, Set<String>> = emptyMap()
}

/** Only for a session where the fill must never be asked: the flag is off, or the session is not in a pick that filters. */
private object NoWatchFill : WatchProviderFill {
    override suspend fun fill(mediaIds: List<MediaId>, region: String) = error("nothing may be filled here")
}
