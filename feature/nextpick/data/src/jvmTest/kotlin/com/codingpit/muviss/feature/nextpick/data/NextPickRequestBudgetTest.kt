package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.nextpick.api.PickAnchor
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.domain.PickAnswer
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.nextpick.domain.PickEngine
import com.codingpit.muviss.feature.nextpick.domain.PickExclusions
import com.codingpit.muviss.feature.nextpick.domain.PickMuting
import com.codingpit.muviss.feature.nextpick.domain.PickResult
import com.codingpit.muviss.feature.nextpick.domain.PickSession
import com.codingpit.muviss.feature.nextpick.domain.PickSessionLength
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
    private val clock: AppClock = fixedClock

    private val library = FakeLibrary(listOf(libraryTitle(movie("1"), WatchStatus.NOT_STARTED), libraryTitle(show("1399"), WatchStatus.FINISHED)))

    private fun standard(anchored: Boolean = true, online: Boolean = true) = PickContext(
        length = PickSessionLength.STANDARD,
        online = online,
        hasBacklog = true,
        hasWatchedTitles = true,
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

    private suspend fun pick(metadata: CountingMetadata, context: PickContext, vararg answers: PickAnswer): PickResult {
        val collection: () -> CollectionApi = { library }
        val engine = PickEngine(
            sources = listOf(
                CollectionBacklogCandidates(collection),
                DiscoverSource(metadata, collection, thisYear = { 2027 }),
                AnchorCandidates(metadata, collection),
            ),
            exclusions = PickExclusions { emptySet() },
            history = CollectionHistoryReader(clock, collection),
            muting = PickMuting { MutedGenres.NONE },
        )
        return engine.pick(criteriaFor(context, *answers), context)
    }

    @Test
    fun `a_liked_anchor_online_costs_two_discover_pages_and_one_each_of_recommendations_and_similar`() = runTest {
        val metadata = CountingMetadata(discover = mapOf(MediaType.MOVIE to listOf(movie("2")), MediaType.TV to listOf(show("3"))))

        pick(metadata, standard(), PickAnswer.Anchor(PickAnchor(finished, like = true)))

        assertEquals(mapOf("discover" to 2, "recommendations" to 1, "similar" to 1), metadata.requests)
        assertEquals(4, metadata.total)
    }

    @Test
    fun `something_different_fetches_no_recommendations_because_the_engine_would_only_penalise_them`() = runTest {
        val metadata = CountingMetadata(discover = mapOf(MediaType.MOVIE to listOf(movie("2"))))

        pick(metadata, standard(), PickAnswer.Anchor(PickAnchor(finished, like = false)))

        assertEquals(mapOf("discover" to 2), metadata.requests)
    }

    @Test
    fun `a_format_other_than_the_anchors_fetches_no_recommendations_and_one_discover_page`() = runTest {
        // The anchor is a show. A movie pick cannot be "more like" it through TMDB's show recommendations.
        val metadata = CountingMetadata(discover = mapOf(MediaType.MOVIE to listOf(movie("2"))))

        pick(metadata, standard(), PickAnswer.Anchor(PickAnchor(finished, like = true)), PickAnswer.FormatAndTime(MediaType.MOVIE, null))

        assertEquals(mapOf("discover" to 1), metadata.requests)
    }

    @Test
    fun `something_new_without_an_anchor_is_discover_only`() = runTest {
        val metadata = CountingMetadata(discover = mapOf(MediaType.MOVIE to listOf(movie("2")), MediaType.TV to listOf(show("3"))))

        pick(metadata, standard(anchored = false), PickAnswer.Source(PickSource.SOMETHING_NEW))

        assertEquals(mapOf("discover" to 2), metadata.requests)
    }

    @Test
    fun `my_list_costs_no_tmdb_requests_at_all`() = runTest {
        val metadata = CountingMetadata()

        val result = pick(metadata, standard(), PickAnswer.Source(PickSource.MY_LIST), PickAnswer.Anchor(PickAnchor(finished, like = true)))

        assertEquals(0, metadata.total)
        assertTrue(result.picks.all { it.candidate.summary.id == MediaId.tmdbMovie("1") }, "my list is the backlog only")
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
