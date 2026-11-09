package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MutedGenres
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** #722: a saved deck position resumes near where the person was, and is never trusted past its TTL. */
class DeckResumeTest {

    private val now = 100_000_000_000L

    @Test
    fun no_saved_position_starts_at_page_one() {
        assertEquals(DeckCursor(), DeckResume.restore(saved = null, nowEpochMs = now))
    }

    @Test
    fun a_restore_rewinds_two_pages_before_the_earliest_unacted_page() {
        val saved = SavedDeckCursor(moviePage = 9, tvPage = 4, moviesResumePage = 7, tvResumePage = 4, savedAtEpochMs = now)

        val restored = DeckResume.restore(saved, nowEpochMs = now + 1_000)

        assertEquals(5, restored.moviePage, "two before the resume page 7")
        assertEquals(2, restored.tvPage, "two before the resume page 4")
        assertTrue(!restored.moviesExhausted && !restored.tvExhausted, "an exhausted flag is never restored: the tail is re-read")
    }

    @Test
    fun a_restore_never_rewinds_before_page_one() {
        val saved = SavedDeckCursor(moviePage = 2, tvPage = 2, moviesResumePage = 1, tvResumePage = 2, savedAtEpochMs = now)

        val restored = DeckResume.restore(saved, nowEpochMs = now)

        assertEquals(1, restored.moviePage)
        assertEquals(1, restored.tvPage)
    }

    @Test
    fun a_position_older_than_the_ttl_is_ignored() {
        val saved = SavedDeckCursor(moviePage = 9, tvPage = 9, moviesResumePage = 9, tvResumePage = 9, savedAtEpochMs = now)

        assertEquals(DeckCursor(), DeckResume.restore(saved, nowEpochMs = now + DeckResume.TTL_MS + 1))
    }

    @Test
    fun a_position_exactly_at_the_ttl_is_still_used() {
        val saved = SavedDeckCursor(moviePage = 9, tvPage = 1, moviesResumePage = 9, tvResumePage = 1, savedAtEpochMs = now)

        assertEquals(7, DeckResume.restore(saved, nowEpochMs = now + DeckResume.TTL_MS).moviePage)
    }

    @Test
    fun a_position_stamped_in_the_future_is_ignored_when_the_clock_went_back() {
        val saved = SavedDeckCursor(moviePage = 9, tvPage = 9, moviesResumePage = 9, tvResumePage = 9, savedAtEpochMs = now + 5_000)

        assertEquals(DeckCursor(), DeckResume.restore(saved, nowEpochMs = now))
    }

    @Test
    fun a_checkpoint_keeps_the_earliest_page_still_holding_an_unacted_card() {
        val cursor = DeckCursor(moviePage = 9, tvPage = 4)

        val saved = DeckResume.checkpoint(cursor, unactedMovie = 6, unactedTv = null, nowEpochMs = now)

        assertEquals(9, saved.moviePage)
        assertEquals(6, saved.moviesResumePage, "a batch spanning pages 6 to 8 must not lose its first pages")
        assertEquals(4, saved.tvResumePage, "nothing unacted for TV, so the next page is the resume point")
        assertEquals(now, saved.savedAtEpochMs)
    }

    @Test
    fun a_checkpoint_with_nothing_unacted_resumes_at_the_next_page() {
        val saved = DeckResume.checkpoint(DeckCursor(moviePage = 3, tvPage = 3), unactedMovie = null, unactedTv = null, nowEpochMs = now)

        assertEquals(3, saved.moviesResumePage)
        assertEquals(3, saved.tvResumePage)
    }

    @Test
    fun two_filters_and_two_languages_have_two_positions() {
        val movies = DeckFilter(type = MediaType.MOVIE)
        val tv = DeckFilter(type = MediaType.TV)
        val muted = DeckFilter(muted = MutedGenres(setOf(GenreKey(MediaType.TV, "10767"))))

        assertNotEquals(DeckResume.key(movies, "en-US"), DeckResume.key(tv, "en-US"))
        assertNotEquals(DeckResume.key(movies, "en-US"), DeckResume.key(muted, "en-US"))
        assertNotEquals(DeckResume.key(movies, "en-US"), DeckResume.key(movies, "es-ES"))
        assertEquals(DeckResume.key(movies, "en-US"), DeckResume.key(DeckFilter(type = MediaType.MOVIE), "en-US"))
    }

    @Test
    fun a_muted_set_is_fingerprinted_in_a_stable_order() {
        val a = GenreKey(MediaType.TV, "10767")
        val b = GenreKey(MediaType.MOVIE, "27")

        assertEquals(
            DeckResume.key(DeckFilter(muted = MutedGenres(setOf(a, b))), "en-US"),
            DeckResume.key(DeckFilter(muted = MutedGenres(setOf(b, a))), "en-US"),
        )
    }

    @Test
    fun a_batch_hands_back_what_it_read_past_the_request_as_overflow() = runTest {
        val source = FakeDeckSource(moviePages = listOf(listOf(movie("1"), movie("2")), listOf(movie("3"), movie("4")), listOf(movie("5"), movie("6"))))

        val batch = DeckLoader(source).load(DeckFilter(type = MediaType.MOVIE), DeckCursor(), excluded = emptySet(), wanted = 3).getOrThrow()

        assertEquals(listOf("1", "2", "3"), batch.cards.map { it.id.external })
        assertEquals(listOf("4"), batch.overflow.map { it.id.external }, "page 2 was read whole, so its last card is kept, not dropped")
        assertEquals(2, batch.pagesRead)
        assertEquals(2, batch.pageOf.getValue(MediaId.tmdbMovie("3")), "a card remembers the page it came from")
        assertEquals(1, batch.pageOf.getValue(MediaId.tmdbMovie("1")))
    }
}
