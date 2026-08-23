package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeckLoaderTest {

    @Test
    fun excludes_everything_already_decided_or_collected() = runTest {
        val source = FakeDeckSource(moviePages = listOf(listOf(movie("1"), movie("2"), movie("3"))))
        val loader = DeckLoader(source)

        val batch = loader.load(
            filter = DeckFilter(type = MediaType.MOVIE),
            cursor = DeckCursor(),
            excluded = setOf(MediaId.tmdbMovie("2")),
        ).getOrThrow()

        assertEquals(listOf("1", "3"), batch.cards.map { it.id.external })
    }

    @Test
    fun interleaves_movies_and_tv_when_no_type_is_selected() = runTest {
        val loader = DeckLoader(
            FakeDeckSource(
                moviePages = listOf(listOf(movie("1")), listOf(movie("2"))),
                tvPages = listOf(listOf(show("10")), listOf(show("11"))),
            ),
        )

        val batch = loader.load(DeckFilter(), DeckCursor(), excluded = emptySet(), wanted = 4).getOrThrow()

        // Alternating, starting with movies — never a run of one type.
        assertEquals(listOf(MediaType.MOVIE, MediaType.TV, MediaType.MOVIE, MediaType.TV), batch.cards.map { it.type })
    }

    @Test
    fun refill_loop_is_bounded_when_every_candidate_is_already_decided() = runTest {
        // The deep-backfill case: page after page of titles the user has
        // already ruled on. Without the cap this would walk the catalogue.
        val pages = List(50) { page -> List(20) { movie("${page * 20 + it}") } }
        val source = FakeDeckSource(moviePages = pages)
        val everything = pages.flatten().map { it.id }.toSet()

        val batch = DeckLoader(source)
            .load(DeckFilter(type = MediaType.MOVIE), DeckCursor(), excluded = everything)
            .getOrThrow()

        assertEquals(emptyList(), batch.cards)
        assertTrue(batch.exhausted)
        assertEquals(DeckLoader.MAX_PAGES_PER_BATCH, source.pageCalls)
    }

    @Test
    fun stops_reading_once_it_has_enough_cards() = runTest {
        val source = FakeDeckSource(moviePages = List(10) { page -> List(20) { movie("${page * 20 + it}") } })

        val batch = DeckLoader(source)
            .load(DeckFilter(type = MediaType.MOVIE), DeckCursor(), excluded = emptySet(), wanted = 5)
            .getOrThrow()

        assertEquals(5, batch.cards.size)
        assertEquals(1, source.pageCalls)
    }

    @Test
    fun a_failed_page_fails_the_batch_rather_than_returning_a_partial_deck() = runTest {
        val loader = DeckLoader(FakeDeckSource(failure = IllegalStateException("offline")))

        val result = loader.load(DeckFilter(), DeckCursor(), excluded = emptySet())

        assertTrue(result.isFailure)
        assertEquals("offline", result.exceptionOrNull()?.message)
    }

    @Test
    fun the_cursor_advances_so_the_next_call_resumes_where_this_one_stopped() = runTest {
        val source = FakeDeckSource(moviePages = listOf(listOf(movie("1")), listOf(movie("2"))))
        val loader = DeckLoader(source)

        val first = loader.load(DeckFilter(type = MediaType.MOVIE), DeckCursor(), emptySet(), wanted = 1).getOrThrow()
        val second = loader.load(DeckFilter(type = MediaType.MOVIE), first.cursor, emptySet(), wanted = 1).getOrThrow()

        assertEquals(listOf("2"), second.cards.map { it.id.external })
    }

    @Test
    fun a_type_that_runs_out_hands_the_deck_over_to_the_other() = runTest {
        val loader = DeckLoader(
            FakeDeckSource(
                moviePages = listOf(listOf(movie("1"))),
                tvPages = listOf(listOf(show("10")), listOf(show("11"))),
            ),
        )

        val batch = loader.load(DeckFilter(), DeckCursor(), emptySet(), wanted = 3).getOrThrow()

        assertEquals(listOf("1", "10", "11"), batch.cards.map { it.id.external })
    }

    @Test
    fun never_hands_back_a_duplicate_within_one_batch() = runTest {
        // TMDB can repeat a title across pages as popularity shifts underneath.
        val repeated = movie("7")
        val loader = DeckLoader(FakeDeckSource(moviePages = listOf(listOf(repeated), listOf(repeated, movie("8")))))

        val batch = loader.load(DeckFilter(type = MediaType.MOVIE), DeckCursor(), emptySet(), wanted = 5).getOrThrow()

        assertEquals(listOf("7", "8"), batch.cards.map { it.id.external })
    }
}
