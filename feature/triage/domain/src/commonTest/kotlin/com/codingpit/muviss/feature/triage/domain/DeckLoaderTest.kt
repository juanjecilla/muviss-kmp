package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.common.flags.SnoozePlacement
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

/**
 * EPIC 42 (ADR 0023): Snoozes that have come due are a second source into the
 * same batch. The catalogue is `/discover` by popularity, so a title postponed
 * three months ago is not on page 1 when it returns — holding onto it and
 * feeding it back is the only way it can ever come back at all.
 */
class DeckLoaderSnoozeTest {

    private val catalogue = List(10) { movie("c$it") }

    @Test
    fun mixed_in_caps_how_many_due_snoozes_one_batch_carries() = runTest {
        val loader = DeckLoader(FakeDeckSource(moviePages = listOf(catalogue)))
        val due = List(40) { movie("s$it") }

        val batch = loader.load(
            filter = DeckFilter(type = MediaType.MOVIE),
            cursor = DeckCursor(),
            excluded = emptySet(),
            dueSnoozes = DueSnoozes(cards = due, placement = SnoozePlacement.MIXED_IN),
        ).getOrThrow()

        // Forty came due; two travel in this batch. The rest drain over later
        // ones rather than burying discovery — the failure ADR 0010 names.
        val returning = batch.cards.count { it.id.external.startsWith("s") }
        assertEquals(DeckLoader.MAX_SNOOZES_PER_BATCH, returning)
    }

    @Test
    fun mixed_in_never_leads_with_a_returning_title() = runTest {
        val loader = DeckLoader(FakeDeckSource(moviePages = listOf(catalogue)))

        val batch = loader.load(
            filter = DeckFilter(type = MediaType.MOVIE),
            cursor = DeckCursor(),
            excluded = emptySet(),
            dueSnoozes = DueSnoozes(cards = listOf(movie("s0"), movie("s1")), placement = SnoozePlacement.MIXED_IN),
        ).getOrThrow()

        // Opening the deck on something already seen reads as nothing having
        // changed, so the first card of a batch is always a new one.
        assertEquals("c0", batch.cards.first().id.external)
        assertTrue(batch.cards.any { it.id.external.startsWith("s") })
    }

    @Test
    fun first_puts_the_oldest_due_titles_at_the_front() = runTest {
        val loader = DeckLoader(FakeDeckSource(moviePages = listOf(catalogue)))

        val batch = loader.load(
            filter = DeckFilter(type = MediaType.MOVIE),
            cursor = DeckCursor(),
            excluded = emptySet(),
            dueSnoozes = DueSnoozes(cards = listOf(movie("s0"), movie("s1")), placement = SnoozePlacement.FIRST),
        ).getOrThrow()

        assertEquals(listOf("s0", "s1"), batch.cards.take(2).map { it.id.external })
    }

    @Test
    fun last_puts_them_behind_everything_new() = runTest {
        val loader = DeckLoader(FakeDeckSource(moviePages = listOf(catalogue.take(3))))

        val batch = loader.load(
            filter = DeckFilter(type = MediaType.MOVIE),
            cursor = DeckCursor(),
            excluded = emptySet(),
            dueSnoozes = DueSnoozes(cards = listOf(movie("s0")), placement = SnoozePlacement.LAST),
        ).getOrThrow()

        assertEquals("s0", batch.cards.last().id.external)
    }

    @Test
    fun a_due_snooze_obeys_the_type_filter() = runTest {
        val loader = DeckLoader(FakeDeckSource(tvPages = listOf(listOf(show("10")))))

        val batch = loader.load(
            filter = DeckFilter(type = MediaType.TV),
            cursor = DeckCursor(),
            excluded = emptySet(),
            // Narrowing the deck to shows must not smuggle a snoozed film back.
            dueSnoozes = DueSnoozes(cards = listOf(movie("s0"), show("s1")), placement = SnoozePlacement.FIRST),
        ).getOrThrow()

        assertTrue(batch.cards.none { it.id.external == "s0" })
        assertTrue(batch.cards.any { it.id.external == "s1" })
    }

    @Test
    fun a_due_snooze_that_is_also_excluded_is_dropped() = runTest {
        val loader = DeckLoader(FakeDeckSource(moviePages = listOf(catalogue)))

        val batch = loader.load(
            filter = DeckFilter(type = MediaType.MOVIE),
            cursor = DeckCursor(),
            excluded = setOf(MediaId.tmdbMovie("s0")),
            dueSnoozes = DueSnoozes(cards = listOf(movie("s0")), placement = SnoozePlacement.FIRST),
        ).getOrThrow()

        assertTrue(batch.cards.none { it.id.external == "s0" })
    }

    @Test
    fun due_snoozes_alone_still_make_a_batch_when_the_catalogue_is_empty() = runTest {
        val loader = DeckLoader(FakeDeckSource(moviePages = listOf(emptyList())))

        val batch = loader.load(
            filter = DeckFilter(type = MediaType.MOVIE),
            cursor = DeckCursor(),
            excluded = emptySet(),
            dueSnoozes = DueSnoozes(cards = listOf(movie("s0"), movie("s1")), placement = SnoozePlacement.MIXED_IN),
        ).getOrThrow()

        // Nothing new to mix them into is not a reason to show nothing.
        assertEquals(listOf("s0", "s1"), batch.cards.map { it.id.external })
    }
}
