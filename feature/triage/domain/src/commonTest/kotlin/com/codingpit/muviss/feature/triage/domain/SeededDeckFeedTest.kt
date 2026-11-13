package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * EPIC 51 (#554, #555): a deck over the titles Next Pick handed over. What has to hold is that it is the engine's
 * list, not a catalogue: rank order kept, nothing read from TMDB, no mutes or Snoozes replayed, and the seeds are
 * the whole deck.
 */
class SeededDeckFeedTest {

    private val clock = FakeClock(NOW_EPOCH_MS)
    private val today = clock.todayEpochDay()

    private fun feed(
        seeds: List<MediaSummary>,
        repository: FakeTriageDecisionRepository = FakeTriageDecisionRepository(),
        snoozes: FakeTriageSnoozeRepository = FakeTriageSnoozeRepository(),
        collection: FakeCollectionApi = FakeCollectionApi(),
    ) = SeededDeckFeed(DeckSeeds { seeds }, repository, snoozes, collection)

    @Test
    fun a_seeded_deck_is_marked_seeded() {
        assertTrue(feed(emptyList()).seeded)
        assertFalse(LoadDeckUseCase(DeckLoader(FakeDeckSource()), FakeTriageDecisionRepository(), FakeTriageSnoozeRepository(), FakeCollectionApi(), clock).seeded)
    }

    @Test
    fun the_seeds_come_out_in_the_order_given_across_movies_and_shows() = runTest {
        val ranked = listOf(show("1"), movie("2"), show("3"), movie("4"))

        val batch = feed(ranked).open(SnoozePlacement.DEFAULT).next(DeckFilter(), DeckCursor(), emptySet(), wanted = 10).getOrThrow()

        assertEquals(ranked.map { it.id }, batch.cards.map { it.id })
    }

    @Test
    fun a_title_in_a_muted_genre_is_still_offered_because_the_engine_already_applied_the_mutes() = runTest {
        val scary = movie("1").copy(genreIds = listOf("27"))

        val batch = feed(listOf(scary)).open(SnoozePlacement.DEFAULT).next(DeckFilter(), DeckCursor(), emptySet(), wanted = 10).getOrThrow()

        assertEquals(listOf(scary.id), batch.cards.map { it.id })
    }

    @Test
    fun the_seeded_batch_is_the_whole_deck_so_the_cursor_is_exhausted_and_the_rest_is_overflow() = runTest {
        val seeds = (1..5).map { movie("$it") }

        val batch = feed(seeds).open(SnoozePlacement.DEFAULT).next(DeckFilter(), DeckCursor(), emptySet(), wanted = 2).getOrThrow()

        assertEquals(seeds.take(2).map { it.id }, batch.cards.map { it.id })
        assertEquals(seeds.drop(2).map { it.id }, batch.overflow.map { it.id })
        assertTrue(batch.cursor.exhaustedFor(DeckFilter()))
    }

    @Test
    fun a_type_filter_narrows_the_seeds_without_reading_a_catalogue() = runTest {
        val seeds = listOf(movie("1"), show("2"), movie("3"))

        val batch = feed(seeds).open(SnoozePlacement.DEFAULT).next(DeckFilter(type = MediaType.MOVIE), DeckCursor(), emptySet(), wanted = 10).getOrThrow()

        assertEquals(listOf(seeds[0].id, seeds[2].id), batch.cards.map { it.id })
    }

    @Test
    fun no_seeds_is_an_empty_exhausted_deck_not_a_catalogue_fallback() = runTest {
        val batch = feed(emptyList()).open(SnoozePlacement.DEFAULT).next(DeckFilter(), DeckCursor(), emptySet(), wanted = 10).getOrThrow()

        assertTrue(batch.exhausted)
        assertTrue(batch.cursor.exhaustedFor(DeckFilter()))
    }

    @Test
    fun a_title_already_ruled_on_saved_or_snoozed_is_not_offered_again() = runTest {
        val decided = movie("1")
        val saved = movie("2")
        val snoozed = movie("3")
        val kept = movie("4")
        val repository = FakeTriageDecisionRepository().also {
            it.record(TriageDecision.of(decided, TriageVerdict.SKIP, nowEpochMs = 1L, resolved = true))
        }
        val collection = FakeCollectionApi().also { it.add(movieDetails(saved)) }
        val snoozes = FakeTriageSnoozeRepository().also { SnoozeUseCase(it, clock).invoke(snoozed, dueAtEpochDay = today + 7) }

        val batch = feed(listOf(decided, saved, snoozed, kept), repository, snoozes, collection)
            .open(SnoozePlacement.DEFAULT)
            .next(DeckFilter(), DeckCursor(), emptySet(), wanted = 10).getOrThrow()

        assertEquals(listOf(kept.id), batch.cards.map { it.id })
    }

    @Test
    fun a_snooze_that_came_due_is_not_replayed_into_a_seeded_deck() = runTest {
        val due = movie("1")
        val snoozes = FakeTriageSnoozeRepository().also { SnoozeUseCase(it, clock).invoke(due, dueAtEpochDay = today) }
        val seed = movie("2")

        val batch = feed(listOf(seed), snoozes = snoozes).open(SnoozePlacement.MIXED_IN)
            .next(DeckFilter(), DeckCursor(), emptySet(), wanted = 10).getOrThrow()

        assertEquals(listOf(seed.id), batch.cards.map { it.id })
    }

    @Test
    fun a_refill_never_offers_a_card_it_already_handed_out() = runTest {
        val seeds = listOf(movie("1"), movie("2"))

        val batch = feed(seeds).open(SnoozePlacement.DEFAULT).next(DeckFilter(), DeckCursor(), alreadyShown = setOf(seeds[0].id), wanted = 10).getOrThrow()

        assertEquals(listOf(seeds[1].id), batch.cards.map { it.id })
    }

    @Test
    fun a_later_handoff_does_not_change_a_deck_already_built() = runTest {
        var current = listOf(movie("1"))
        val feed = SeededDeckFeed(DeckSeeds { current }, FakeTriageDecisionRepository(), FakeTriageSnoozeRepository(), FakeCollectionApi())
        current = listOf(movie("9"))

        val batch = feed.open(SnoozePlacement.DEFAULT).next(DeckFilter(), DeckCursor(), emptySet(), wanted = 10).getOrThrow()

        assertEquals(listOf(movie("1").id), batch.cards.map { it.id })
    }

    @Test
    fun a_seeded_deck_always_starts_at_page_one() = runTest {
        val cursor = feed(emptyList()).restoreCursor(DeckFilter())

        assertEquals(DeckCursor(), cursor)
    }
}
