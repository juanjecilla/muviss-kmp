@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.Genre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EPIC 51 (#554, #555): the deck Next Pick hands over. It is Triage's screen over the seeds, so the verdicts and undo
 * are the catalogue deck's, but the list is the engine's, read from nowhere else, and it is never saved as a position.
 */
class SeededDeckViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val filmA = movie("1")
    private val filmB = movie("2")
    private val showA = show("10")

    @Test
    fun a_seeded_deck_offers_its_titles_in_the_order_handed_over_and_reads_no_catalogue() = runTest {
        val harness = TriageHarness(movies = listOf(movie("99")), tv = listOf(show("98")))
        val ranked = listOf(showA, filmA, filmB)

        val vm = harness.seededViewModel(ranked)
        advanceUntilIdle()

        assertTrue(vm.state.value.seeded)
        assertEquals(ranked.map { it.id }, vm.state.value.cards.map { it.id })
        assertEquals(emptyList(), harness.source.requestedPages)
        assertEquals(emptyList(), harness.source.tvExcludedRequests)
    }

    @Test
    fun a_seeded_deck_has_no_genre_chips_to_narrow_it_by() = runTest {
        val vm = TriageHarness(movies = listOf(filmA), genres = listOf(Genre("28", "Action"))).seededViewModel(listOf(filmA))
        advanceUntilIdle()

        assertTrue(vm.state.value.movieGenres.isEmpty())
        assertTrue(vm.state.value.tvGenres.isEmpty())
    }

    @Test
    fun an_empty_handoff_is_the_picks_done_state_and_never_reads_the_catalogue() = runTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))

        val vm = harness.seededViewModel(emptyList())
        advanceUntilIdle()

        assertNull(vm.state.value.topCard)
        assertTrue(vm.state.value.exhausted)
        assertFalse(vm.state.value.keepLookingAvailable)
        assertFalse(vm.state.value.searchingDeeper)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
        assertEquals(emptyList(), harness.source.requestedPages)
    }

    @Test
    fun a_seeded_deck_that_runs_out_is_exhausted_and_never_offers_to_keep_looking() = runTest {
        val vm = TriageHarness(movies = listOf(filmA, filmB)).seededViewModel(listOf(filmA, filmB))
        advanceUntilIdle()

        vm.onDecide(TriageVerdict.LATER, viaGesture = false)
        vm.onDecide(TriageVerdict.LATER, viaGesture = false)
        advanceUntilIdle()

        assertNull(vm.state.value.topCard)
        assertTrue(vm.state.value.exhausted)
        assertFalse(vm.state.value.keepLookingAvailable)
    }

    @Test
    fun a_seeded_deck_saves_no_position_even_as_it_is_swiped_while_a_catalogue_deck_does() = runTest {
        val store = FakeDeckCursorStore()
        val harness = TriageHarness(movies = listOf(filmA, filmB), cursors = store)

        val seeded = harness.seededViewModel(listOf(filmA, filmB))
        advanceUntilIdle()
        seeded.onDecide(TriageVerdict.LATER, viaGesture = false)
        seeded.onDecide(TriageVerdict.SKIP, viaGesture = false)
        advanceUntilIdle()
        assertTrue(store.saved.isEmpty(), "a seeded deck must not overwrite the catalogue position")

        // The same store is written by the catalogue deck, so the assertion above is not vacuous.
        val catalogue = harness.viewModel()
        advanceUntilIdle()
        catalogue.onDecide(TriageVerdict.SKIP, viaGesture = false)
        advanceUntilIdle()
        assertTrue(store.saved.isNotEmpty())
    }

    @Test
    fun a_seed_swiped_away_stays_away_through_a_reset() = runTest {
        val vm = TriageHarness(movies = listOf(filmA, filmB)).seededViewModel(listOf(filmA, filmB))
        advanceUntilIdle()
        vm.onDecide(TriageVerdict.LATER, viaGesture = false)
        advanceUntilIdle()

        vm.retry()
        advanceUntilIdle()

        assertEquals(listOf(filmB.id), vm.state.value.cards.map { it.id })
    }

    @Test
    fun a_seed_snoozed_stays_out_through_a_reset() = runTest {
        val vm = TriageHarness(movies = listOf(filmA, filmB)).seededViewModel(listOf(filmA, filmB))
        advanceUntilIdle()
        // The default period is a week, so the snooze commits at once rather than opening the sheet.
        vm.onSnooze()
        advanceUntilIdle()

        vm.retry()
        advanceUntilIdle()

        assertEquals(listOf(filmB.id), vm.state.value.cards.map { it.id })
    }

    @Test
    fun undo_of_a_seed_puts_it_back_on_top() = runTest {
        val vm = TriageHarness(movies = listOf(filmA, filmB)).seededViewModel(listOf(filmA, filmB))
        advanceUntilIdle()
        vm.onDecide(TriageVerdict.LATER, viaGesture = false)
        advanceUntilIdle()

        vm.onUndo()
        advanceUntilIdle()

        assertEquals(filmA.id, vm.state.value.topCard?.id)
    }
}
