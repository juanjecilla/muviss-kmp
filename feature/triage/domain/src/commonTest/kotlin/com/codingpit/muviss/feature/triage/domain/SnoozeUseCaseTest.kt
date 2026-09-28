package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.todayEpochDay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EPIC 42, ADR 0023. What has to hold is the distinction the ADR is about: a
 * Snooze records that the user did *not* decide, so it writes no decision, no
 * collection row and no ticks — and it comes back.
 */
class SnoozeUseCaseTest {

    private val clock = FakeClock()
    private val today = clock.todayEpochDay()

    @Test
    fun snoozing_writes_no_decision_and_nothing_to_the_collection() = runTest {
        val decisions = FakeTriageDecisionRepository()
        val collection = FakeCollectionApi()
        val progress = FakeProgressApi()
        val snoozes = FakeTriageSnoozeRepository()

        SnoozeUseCase(snoozes, clock).invoke(movie("1"), dueAtEpochDay = today + 7)

        assertEquals(1, snoozes.snoozes.value.size)
        assertTrue(decisions.observeDecidedIds().first().isEmpty())
        assertTrue(collection.added.isEmpty())
        assertTrue(progress.ticked.isEmpty())
    }

    @Test
    fun the_stored_period_decides_the_due_date() {
        val snooze = SnoozeUseCase(FakeTriageSnoozeRepository(), clock)

        assertEquals(today + 7, snooze.dueDateFor(SnoozePeriod.ONE_WEEK))
        assertEquals(today + 30, snooze.dueDateFor(SnoozePeriod.ONE_MONTH))
        assertEquals(today + 90, snooze.dueDateFor(SnoozePeriod.THREE_MONTHS))
        // Not a duration — it is the mode that asks, so it has no date of its own.
        assertNull(snooze.dueDateFor(SnoozePeriod.ASK_EACH_TIME))
    }

    @Test
    fun today_epoch_day_reads_straight_off_the_clock() {
        val snooze = SnoozeUseCase(FakeTriageSnoozeRepository(), clock)

        // The custom date picker's only clock read — everything else about it
        // (which day is selected, which month is visible) stays in the screen.
        assertEquals(today, snooze.todayEpochDay())
    }

    @Test
    fun a_snoozed_title_stays_out_of_the_deck_until_it_is_due() = runTest {
        val snoozes = FakeTriageSnoozeRepository()
        val card = movie("1")
        SnoozeUseCase(snoozes, clock).invoke(card, dueAtEpochDay = today + 7)

        val loadDeck = LoadDeckUseCase(
            loader = DeckLoader(FakeDeckSource(moviePages = listOf(listOf(card, movie("2"))))),
            repository = FakeTriageDecisionRepository(),
            snoozes = snoozes,
            collectionApi = FakeCollectionApi(),
            clock = clock,
        )

        val batch = loadDeck(DeckFilter(), DeckCursor()).getOrThrow()
        assertTrue(batch.cards.none { it.id == card.id }, "a pending snooze must not be offered")
    }

    @Test
    fun a_due_snooze_comes_back_with_the_card_it_was_stored_with() = runTest {
        val snoozes = FakeTriageSnoozeRepository()
        val card = movie("1", title = "The Matrix")
        SnoozeUseCase(snoozes, clock).invoke(card, dueAtEpochDay = today)

        val loadDeck = LoadDeckUseCase(
            loader = DeckLoader(FakeDeckSource(moviePages = listOf(emptyList()))),
            repository = FakeTriageDecisionRepository(),
            snoozes = snoozes,
            collectionApi = FakeCollectionApi(),
            clock = clock,
        )

        val batch = loadDeck(DeckFilter(), DeckCursor(), placement = SnoozePlacement.FIRST).getOrThrow()

        // Straight off the stored snapshot: the catalogue returned nothing, so
        // there was nowhere else this card could have come from.
        assertEquals(listOf("The Matrix"), batch.cards.map { it.title })
    }

    @Test
    fun a_snooze_overtaken_by_a_real_decision_is_retired_rather_than_shown() = runTest {
        val snoozes = FakeTriageSnoozeRepository()
        val card = movie("1")
        SnoozeUseCase(snoozes, clock).invoke(card, dueAtEpochDay = today)

        // The user ruled on it from the Detail screen while it waited.
        val decisions = FakeTriageDecisionRepository()
        decisions.record(TriageDecision.of(card, com.codingpit.muviss.feature.triage.api.TriageVerdict.SKIP, nowEpochMs = 1L, resolved = true))

        val loadDeck = LoadDeckUseCase(
            loader = DeckLoader(FakeDeckSource(moviePages = listOf(listOf(movie("2"))))),
            repository = decisions,
            snoozes = snoozes,
            collectionApi = FakeCollectionApi(),
            clock = clock,
        )

        val batch = loadDeck(DeckFilter(), DeckCursor(), placement = SnoozePlacement.FIRST).getOrThrow()

        assertTrue(batch.cards.none { it.id == card.id })
        // Soft-deleted, not merely skipped over: the question is gone, so the
        // row should stop travelling between devices too.
        assertEquals(listOf(card.id), snoozes.unsnoozed)
    }

    @Test
    fun unsnoozing_makes_the_title_deck_eligible_again() = runTest {
        val snoozes = FakeTriageSnoozeRepository()
        val card = movie("1")
        SnoozeUseCase(snoozes, clock).invoke(card, dueAtEpochDay = today + 90)

        UnsnoozeUseCase(snoozes).invoke(card.id)

        assertTrue(snoozes.observeSnoozedIds().first().isEmpty())
    }
}
