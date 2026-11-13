package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.network.WatchProviderFill
import com.codingpit.muviss.core.network.WatchProviderLookup
import com.codingpit.muviss.feature.nextpick.api.PickAnchor
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.domain.PickCandidates
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteKind
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #740: "only my services" narrows the backlog and the anchor's candidates by their cached providers, never by a request. */
class OwnServicesCandidatesTest {

    private val netflix = TasteItem(TasteKind.SERVICE, "8", "Netflix")
    private val disney = TasteItem(TasteKind.SERVICE, "337", "Disney+")
    private val services = FakeTasteProfile(listOf(netflix, disney))
    private val anchor = MediaId.tmdbTv("1399")

    private val onMyServices = PickCriteria(source = PickSource.MY_LIST, onlyMyServices = true)

    /** Cached providers keyed by title: region and flatrate ids, as the cache stores them. */
    private class FakeLookup(private val entries: Map<MediaId, Pair<String, Set<String>>>) : WatchProviderLookup {
        override suspend fun cachedFlatrateIds(mediaIds: Set<MediaId>, region: String): Map<MediaId, Set<String>> = entries
            .filterKeys { it in mediaIds }
            .filterValues { (cachedRegion, _) -> cachedRegion == region }
            .mapValues { (_, cached) -> cached.second }
    }

    private object NoLookup : WatchProviderLookup {
        override suspend fun cachedFlatrateIds(mediaIds: Set<MediaId>, region: String): Map<MediaId, Set<String>> = error("the flag is off, so nothing is looked up")
    }

    /** Fills nothing, as a fill that failed would: the titles stay unknown. */
    private object FailingFill : WatchProviderFill {
        override suspend fun fill(mediaIds: List<MediaId>, region: String) = Unit
    }

    /** A fill that must not be reached: offline, or the flag is off. */
    private object NoFill : WatchProviderFill {
        override suspend fun fill(mediaIds: List<MediaId>, region: String) = error("nothing may be fetched here")
    }

    /** Records every title asked about and writes [answers] into [lookup], as the real fill writes the cache. */
    private class RecordingFill(private val lookup: MutableWatchCache, private val answers: (MediaId) -> Set<String>) : WatchProviderFill {
        val asked = mutableListOf<MediaId>()
        val regions = mutableListOf<String>()

        override suspend fun fill(mediaIds: List<MediaId>, region: String) {
            regions += region
            for (id in mediaIds) {
                asked += id
                lookup.entries[id] = region to answers(id)
            }
        }
    }

    private val genreIds = LibraryGenreIds(liveCatalogue = { emptyList<Genre>() }, language = { "en-US" })

    private fun backlog(
        library: FakeLibrary,
        lookup: WatchProviderLookup,
        region: String = "US",
        fill: WatchProviderFill = FailingFill,
    ): PickCandidates = OwnServicesCandidates(
        inner = CollectionBacklogCandidates({ library }, genreIds, fixedClock),
        taste = { services },
        lookup = { lookup },
        fill = { fill },
        region = { region },
    )

    @Test
    fun `a_backlog_title_cached_on_one_of_my_services_is_included`() = runTest {
        val library = FakeLibrary(listOf(libraryTitle(movie("1"), WatchStatus.NOT_STARTED)))
        val lookup = FakeLookup(mapOf(MediaId.tmdbMovie("1") to ("US" to setOf("8", "9"))))

        val picked = backlog(library, lookup).gather(onMyServices, PickContext(online = true))

        assertEquals(listOf(MediaId.tmdbMovie("1")), picked.map { it.id })
    }

    @Test
    fun `a_backlog_title_cached_only_on_other_services_is_excluded`() = runTest {
        val library = FakeLibrary(listOf(libraryTitle(movie("2"), WatchStatus.NOT_STARTED)))
        val lookup = FakeLookup(mapOf(MediaId.tmdbMovie("2") to ("US" to setOf("9"))))

        val picked = backlog(library, lookup).gather(onMyServices, PickContext(online = true))

        assertTrue(picked.isEmpty())
    }

    @Test
    fun `a_backlog_title_with_no_cached_providers_is_excluded_not_guessed_at`() = runTest {
        val library = FakeLibrary(listOf(libraryTitle(movie("3"), WatchStatus.NOT_STARTED)))

        val picked = backlog(library, FakeLookup(emptyMap())).gather(onMyServices, PickContext(online = true))

        assertTrue(picked.isEmpty(), "a title never fetched is not known to be on my services")
    }

    @Test
    fun `a_cached_entry_for_another_region_is_treated_as_no_cache`() = runTest {
        val library = FakeLibrary(listOf(libraryTitle(movie("4"), WatchStatus.NOT_STARTED)))
        val lookup = FakeLookup(mapOf(MediaId.tmdbMovie("4") to ("GB" to setOf("8"))))

        val picked = backlog(library, lookup, region = "US").gather(onMyServices, PickContext(online = true))

        assertTrue(picked.isEmpty())
    }

    @Test
    fun `a_library_larger_than_one_lookup_batch_is_looked_up_in_batches_and_filtered_whole`() = runTest {
        val titles = (1..1200).map { libraryTitle(movie("$it"), WatchStatus.NOT_STARTED) }
        val inner = FakeLookup(mapOf(MediaId.tmdbMovie("1150") to ("US" to setOf("8"))))
        val recording = RecordingLookup(inner)

        val picked = backlog(FakeLibrary(titles), recording).gather(onMyServices, PickContext(online = true))

        assertEquals(listOf(MediaId.tmdbMovie("1150")), picked.map { it.id })
        assertTrue(recording.batchSizes.size > 1, "1200 ids must not go in one lookup")
        assertTrue(recording.batchSizes.all { it <= 500 }, "each lookup stays within one batch: ${recording.batchSizes}")
        // Two reads: the cache before the fill and again after it. Both go in batches, and each id is in both.
        assertEquals(2400, recording.batchSizes.sum())
    }

    /** Records how many ids each lookup call carries, and answers from [inner]. */
    private class RecordingLookup(private val inner: WatchProviderLookup) : WatchProviderLookup {
        val batchSizes = mutableListOf<Int>()

        override suspend fun cachedFlatrateIds(mediaIds: Set<MediaId>, region: String): Map<MediaId, Set<String>> {
            batchSizes += mediaIds.size
            return inner.cachedFlatrateIds(mediaIds, region)
        }
    }

    @Test
    fun `with_the_flag_off_every_candidate_passes_and_nothing_is_looked_up`() = runTest {
        val library = FakeLibrary(listOf(libraryTitle(movie("5"), WatchStatus.NOT_STARTED)))

        val picked = backlog(library, NoLookup).gather(PickCriteria(source = PickSource.MY_LIST), PickContext(online = true))

        assertEquals(listOf(MediaId.tmdbMovie("5")), picked.map { it.id })
    }

    @Test
    fun `with_no_services_in_the_taste_profile_the_flag_narrows_nothing_as_discover_does`() = runTest {
        val library = FakeLibrary(listOf(libraryTitle(movie("6"), WatchStatus.NOT_STARTED)))
        val source = OwnServicesCandidates(
            inner = CollectionBacklogCandidates({ library }, genreIds, fixedClock),
            taste = { FakeTasteProfile() },
            lookup = { NoLookup },
            fill = { NoFill },
            region = { "US" },
        )

        val picked = source.gather(onMyServices, PickContext(online = true))

        assertEquals(listOf(MediaId.tmdbMovie("6")), picked.map { it.id })
    }

    @Test
    fun `an_anchor_recommendation_is_filtered_by_its_cached_providers_too`() = runTest {
        val metadata = CountingMetadata(recommended = listOf(movie("7"), movie("8")))
        val source = OwnServicesCandidates(
            inner = AnchorCandidates(metadata, { FakeLibrary() }),
            taste = { services },
            lookup = { FakeLookup(mapOf(MediaId.tmdbMovie("7") to ("US" to setOf("337")))) },
            fill = { FailingFill },
            region = { "US" },
        )

        val picked = source.gather(
            PickCriteria(anchor = PickAnchor(anchor, like = true), onlyMyServices = true),
            PickContext(anchorCandidate = anchor),
        )

        assertEquals(listOf(MediaId.tmdbMovie("7")), picked.map { it.id })
        assertEquals(mapOf("recommendations" to 1, "similar" to 1), metadata.requests, "the filter adds no request of its own")
    }

    @Test
    fun `an_uncached_title_is_filled_then_judged_on_what_the_fill_wrote`() = runTest {
        val library = FakeLibrary(listOf(libraryTitle(movie("20"), WatchStatus.NOT_STARTED), libraryTitle(movie("21"), WatchStatus.NOT_STARTED)))
        val lookup = MutableWatchCache()
        val fill = RecordingFill(lookup) { id -> if (id == MediaId.tmdbMovie("20")) setOf("8") else setOf("9") }

        val picked = backlog(library, lookup, fill = fill).gather(onMyServices, PickContext(online = true))

        assertEquals(listOf(MediaId.tmdbMovie("20")), picked.map { it.id })
        assertEquals(listOf(MediaId.tmdbMovie("20"), MediaId.tmdbMovie("21")), fill.asked)
        assertEquals(listOf("US"), fill.regions, "the fill is asked for the region the pick is judged in")
    }

    @Test
    fun `a_fill_asks_only_the_first_cap_of_uncached_titles_and_the_rest_are_excluded_not_claimed`() = runTest {
        val library = FakeLibrary((1..25).map { libraryTitle(movie("$it"), WatchStatus.NOT_STARTED) })
        val lookup = MutableWatchCache()
        val fill = RecordingFill(lookup) { setOf("8") }

        val picked = backlog(library, lookup, fill = fill).gather(onMyServices, PickContext(online = true))

        assertEquals(MAX_PROVIDER_FILLS_PER_PICK, fill.asked.size, "the budget caps what is asked")
        assertEquals((1..10).map { MediaId.tmdbMovie("$it") }, fill.asked, "in candidate order")
        assertEquals((1..10).map { MediaId.tmdbMovie("$it") }, picked.map { it.id }, "only the filled titles, all on a service")
    }

    @Test
    fun `a_title_the_fill_could_not_answer_is_excluded_and_nothing_is_recorded_for_it`() = runTest {
        // A failed fetch writes nothing, so the title stays unknown and the next pick may ask again.
        val library = FakeLibrary(listOf(libraryTitle(movie("30"), WatchStatus.NOT_STARTED)))
        val lookup = MutableWatchCache()

        val picked = backlog(library, lookup, fill = FailingFill).gather(onMyServices, PickContext(online = true))

        assertTrue(picked.isEmpty(), "unknown is not claimed as on a service")
        assertTrue(lookup.entries.isEmpty(), "and nothing is written as on no service either")
    }

    @Test
    fun `a_fill_never_refetches_a_title_the_cache_already_answers_for_this_region`() = runTest {
        val library = FakeLibrary(listOf(libraryTitle(movie("40"), WatchStatus.NOT_STARTED), libraryTitle(movie("41"), WatchStatus.NOT_STARTED)))
        val lookup = MutableWatchCache().apply { entries[MediaId.tmdbMovie("40")] = "US" to setOf("9") }
        val fill = RecordingFill(lookup) { setOf("8") }

        val picked = backlog(library, lookup, fill = fill).gather(onMyServices, PickContext(online = true))

        assertEquals(listOf(MediaId.tmdbMovie("41")), fill.asked)
        assertEquals(listOf(MediaId.tmdbMovie("41")), picked.map { it.id })
    }

    @Test
    fun `offline_nothing_is_filled_and_the_cache_alone_decides`() = runTest {
        val library = FakeLibrary(listOf(libraryTitle(movie("50"), WatchStatus.NOT_STARTED), libraryTitle(movie("51"), WatchStatus.NOT_STARTED)))
        val lookup = MutableWatchCache().apply { entries[MediaId.tmdbMovie("50")] = "US" to setOf("8") }

        val picked = backlog(library, lookup, fill = NoFill).gather(onMyServices, PickContext(online = false))

        assertEquals(listOf(MediaId.tmdbMovie("50")), picked.map { it.id })
    }
}
