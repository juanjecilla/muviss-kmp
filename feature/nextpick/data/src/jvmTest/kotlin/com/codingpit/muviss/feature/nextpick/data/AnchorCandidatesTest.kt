package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.feature.nextpick.api.PickAnchor
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.domain.CandidateSource
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #548: the anchor's TMDB recommendations and similar titles, as candidates the engine can filter. */
class AnchorCandidatesTest {

    private val anchor = MediaId.tmdbTv("1399")
    private val liked = PickCriteria(anchor = PickAnchor(anchor, like = true))

    @Test
    fun `recommended_and_similar_titles_come_back_as_their_own_sources_with_the_library_status`() = runTest {
        val watched = movie("4")
        val fresh = movie("5")
        val library = FakeLibrary(listOf(libraryTitle(watched, WatchStatus.WATCHED)))
        val metadata = CountingMetadata(recommended = listOf(watched), similarTitles = listOf(fresh))

        val candidates = AnchorCandidates(metadata, { library }).gather(liked, PickContext(anchorCandidate = anchor))

        val recommended = candidates.single { it.source == CandidateSource.RECOMMENDED }
        val similar = candidates.single { it.source == CandidateSource.SIMILAR }
        assertEquals(watched.id, recommended.id)
        assertEquals(WatchStatus.WATCHED, recommended.libraryStatus, "a title the library holds is not new")
        assertEquals(fresh.id, similar.id)
        assertNull(similar.libraryStatus)
    }

    @Test
    fun `the_title_just_finished_is_never_one_of_its_own_candidates`() = runTest {
        val metadata = CountingMetadata(recommended = listOf(show("1399"), movie("6")))

        val candidates = AnchorCandidates(metadata, { FakeLibrary() }).gather(liked, PickContext(anchorCandidate = anchor))

        assertEquals(listOf(MediaId.tmdbMovie("6")), candidates.map { it.id })
    }

    @Test
    fun `something_different_gathers_nothing_and_asks_nothing`() = runTest {
        val metadata = CountingMetadata(recommended = listOf(movie("6")))

        val candidates = AnchorCandidates(metadata, { FakeLibrary() })
            .gather(PickCriteria(anchor = PickAnchor(anchor, like = false)), PickContext(anchorCandidate = anchor))

        assertTrue(candidates.isEmpty())
        assertEquals(0, metadata.total)
    }

    @Test
    fun `offline_and_my_list_gather_nothing_and_ask_nothing`() = runTest {
        val metadata = CountingMetadata(recommended = listOf(movie("6")))
        val source = AnchorCandidates(metadata, { FakeLibrary() })

        assertTrue(source.gather(liked, PickContext(anchorCandidate = anchor, online = false)).isEmpty())
        assertTrue(source.gather(liked.copy(source = PickSource.MY_LIST), PickContext(anchorCandidate = anchor)).isEmpty())
        assertEquals(0, metadata.total)
    }

    @Test
    fun `a_format_other_than_the_anchors_gathers_nothing_because_tmdb_would_answer_with_the_wrong_format`() = runTest {
        val metadata = CountingMetadata(recommended = listOf(movie("6")))

        val candidates = AnchorCandidates(metadata, { FakeLibrary() })
            .gather(liked.copy(type = MediaType.MOVIE), PickContext(anchorCandidate = anchor))

        assertTrue(candidates.isEmpty())
        assertEquals(0, metadata.total)
    }

    @Test
    fun `no_anchor_means_no_gather_at_all`() = runTest {
        val metadata = CountingMetadata(recommended = listOf(movie("6")))

        assertTrue(AnchorCandidates(metadata, { FakeLibrary() }).gather(PickCriteria(), PickContext()).isEmpty())
        assertEquals(0, metadata.total)
    }
}
